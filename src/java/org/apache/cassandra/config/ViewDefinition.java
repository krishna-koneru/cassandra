/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.config;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ToStringBuilder;

import org.antlr.runtime.RecognitionException;
import org.apache.cassandra.cql3.CQLFragmentParser;
import org.apache.cassandra.cql3.ColumnIdentifier;
import org.apache.cassandra.cql3.CqlParser;
import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.cql3.Relation;
import org.apache.cassandra.cql3.statements.ParsedStatement;
import org.apache.cassandra.cql3.statements.SelectStatement;
import org.apache.cassandra.db.filter.ColumnFilter;
import org.apache.cassandra.db.view.View;
import org.apache.cassandra.exceptions.SyntaxException;
import org.apache.cassandra.service.ClientState;

public class ViewDefinition
{
    public final String ksName;
    public final String viewName;
    public final UUID baseTableId;
    public final String baseTableName;
    public final boolean includeAllColumns;
    // To save some space,metadata only carries denormalized columns.
    // view metadata does not necessarily contain all basetable columns that it uses.
    // Use queriedColumns instead to check if a view uses/depends on a column.
    private final CFMetaData metadata;

    private Map<ByteBuffer, ColumnDefinition> queriedColumns;
    public SelectStatement.RawStatement select;
    public String whereClause;

    public ViewDefinition(ViewDefinition def)
    {
        this(def.ksName, def.viewName, def.baseTableId, def.baseTableName, def.includeAllColumns, def.select, def.whereClause, def.metadata);
    }

    /**
     * @param viewName          Name of the view
     * @param baseTableId       Internal ID of the table which this view is based off of
     * @param includeAllColumns Whether to include all columns or not
     */
    public ViewDefinition(String ksName, String viewName, UUID baseTableId, String baseTableName, boolean includeAllColumns, SelectStatement.RawStatement select, String whereClause, CFMetaData metadata)
    {
        this.ksName = ksName;
        this.viewName = viewName;
        this.baseTableId = baseTableId;
        this.baseTableName = baseTableName;
        this.includeAllColumns = includeAllColumns;
        this.select = select;
        this.whereClause = whereClause;
        this.metadata = metadata;
        // Base table might not be initialized yet, so prepare select might fail.getQueriedColumns() will update this later.
        this.queriedColumns = null;
    }


    public Map<ByteBuffer, ColumnDefinition> getQueriedColumns()
    {
        if (queriedColumns == null)
            UpdateQueriedColumns();

        assert this.queriedColumns != null : "Could not get columns used in View SELECT";

        return queriedColumns;
    }

    private void UpdateQueriedColumns()
    {
        SelectStatement selectStatement;
        ClientState state = ClientState.forInternalCalls();
        state.setKeyspace(baseTableMetadata().ksName);
        this.select.prepareKeyspace(state);
        ParsedStatement.Prepared prepared = this.select.prepare(true);
        selectStatement = (SelectStatement) prepared.statement;


        ColumnFilter cf = selectStatement.queriedColumns();


        this.queriedColumns = StreamSupport.stream(cf.queriedColumns().spliterator(),true)
                                           .collect(Collectors.toMap(
                                           e -> e.name.bytes,
                                           e -> e
                                           ));
        // Also make sure primary key columns are included.
        this.queriedColumns.putAll(this.metadata.getColumnMetadata());
    }

    /**
     * NOTE : metadata does not contain all the columns that view depends on. Use queriedColums() for column information
     * @return view metadata.
     */
    public CFMetaData getMetadata()
    {
        return metadata;
    }

    /**
     * @return true if the view specified by this definition will include the column, false otherwise
     */
    public boolean includes(ColumnIdentifier column)
    {
        return getQueriedColumns().get(column.bytes) != null ;
    }

    public ViewDefinition copy()
    {
        return new ViewDefinition(ksName, viewName, baseTableId, baseTableName, includeAllColumns, select, whereClause, metadata.copy());
    }

    public CFMetaData baseTableMetadata()
    {
        return Schema.instance.getCFMetaData(baseTableId);
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;

        if (!(o instanceof ViewDefinition))
            return false;

        ViewDefinition other = (ViewDefinition) o;
        return Objects.equals(ksName, other.ksName)
               && Objects.equals(viewName, other.viewName)
               && Objects.equals(baseTableId, other.baseTableId)
               && Objects.equals(includeAllColumns, other.includeAllColumns)
               && Objects.equals(whereClause, other.whereClause)
               && Objects.equals(metadata, other.metadata);
    }

    @Override
    public int hashCode()
    {
        return new HashCodeBuilder(29, 1597)
               .append(ksName)
               .append(viewName)
               .append(baseTableId)
               .append(includeAllColumns)
               .append(whereClause)
               .append(metadata)
               .toHashCode();
    }

    @Override
    public String toString()
    {
        return new ToStringBuilder(this)
               .append("ksName", ksName)
               .append("viewName", viewName)
               .append("baseTableId", baseTableId)
               .append("baseTableName", baseTableName)
               .append("includeAllColumns", includeAllColumns)
               .append("whereClause", whereClause)
               .append("metadata", metadata)
               .append("queriedColumns", getQueriedColumns().values())
               .toString();
    }

    /**
     * Replace the column 'from' with 'to' in this materialized view definition's partition,
     * clustering, or included columns.
     * @param from the existing column
     * @param to the new column
     */
    public void renameColumn(ColumnIdentifier from, ColumnIdentifier to)
    {
        if (metadata.getColumnDefinition(from) != null)
        {
            metadata.renameColumn(from, to);
        }
        else
        {
            assert includes(from) : "trying to rename non-existent column in view.";
        }

        // convert whereClause to Relations, rename ids in Relations, then convert back to whereClause
        List<Relation> relations = whereClauseToRelations(whereClause);
        ColumnDefinition.Raw fromRaw = ColumnDefinition.Raw.forQuoted(from.toString());
        ColumnDefinition.Raw toRaw = ColumnDefinition.Raw.forQuoted(to.toString());
        List<Relation> newRelations = relations.stream()
                .map(r -> r.renameIdentifier(fromRaw, toRaw))
                .collect(Collectors.toList());

        this.whereClause = View.relationsToWhereClause(newRelations);
        String rawSelect = View.buildSelectStatement(baseTableName, metadata.allColumns(), whereClause);
        this.select = (SelectStatement.RawStatement) QueryProcessor.parseStatement(rawSelect);
        // basetable metadata has not been updated yet, so prepare select will fail. call to getQueriedColumns() will
        // update this later.
        this.queriedColumns = null ;

    }

    private static List<Relation> whereClauseToRelations(String whereClause)
    {
        try
        {
            List<Relation> relations = CQLFragmentParser.parseAnyUnhandled(CqlParser::whereClause, whereClause).build().relations;

            return relations;
        }
        catch (RecognitionException | SyntaxException exc)
        {
            throw new RuntimeException("Unexpected error parsing materialized view's where clause while handling column rename: ", exc);
        }
    }

}
