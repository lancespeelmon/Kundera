/*******************************************************************************
 * * Copyright 2012 Impetus Infotech.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 ******************************************************************************/
package com.impetus.kundera.configure;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.persistence.metamodel.Attribute;
import javax.persistence.metamodel.EmbeddableType;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.Metamodel;
import javax.persistence.metamodel.SingularAttribute;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.impetus.kundera.PersistenceProperties;
import com.impetus.kundera.client.ClientResolver;
import com.impetus.kundera.configure.schema.ColumnInfo;
import com.impetus.kundera.configure.schema.EmbeddedColumnInfo;
import com.impetus.kundera.configure.schema.TableInfo;
import com.impetus.kundera.metadata.KunderaMetadataManager;
import com.impetus.kundera.metadata.model.ApplicationMetadata;
import com.impetus.kundera.metadata.model.EntityMetadata;
import com.impetus.kundera.metadata.model.EntityMetadata.Type;
import com.impetus.kundera.metadata.model.KunderaMetadata;
import com.impetus.kundera.metadata.model.MetamodelImpl;
import com.impetus.kundera.metadata.model.Relation;
import com.impetus.kundera.metadata.model.Relation.ForeignKey;
import com.impetus.kundera.metadata.model.attributes.AbstractAttribute;

/**
 * Schema configuration implementation to support ddl_schema_creation
 * functionality. e.g. kundera_ddl_auto_prepare
 * (create,create-drop,validate,update)
 * 
 * @author Kuldeep.Kumar
 * 
 */
public class SchemaConfiguration implements Configuration
{

    /** The log. */
    private static Logger log = LoggerFactory.getLogger(SchemaConfiguration.class);

    /** Holding persistence unit instances. */
    private String[] persistenceUnits;

    /**
     * pu to schema metadata map .
     */
    private Map<String, List<TableInfo>> puToSchemaMetadata;

    /**
     * Constructor using persistence units as parameter.
     * 
     * @param persistenceUnits
     *            persistence units.
     */
    public SchemaConfiguration(String... persistenceUnits)
    {
        this.persistenceUnits = persistenceUnits;
    }

    @Override
    /**
     * configure method responsible for creating pu to schema metadata map for each entity in class path.
     * 
     */
    public void configure()
    {

        ApplicationMetadata appMetadata = KunderaMetadata.INSTANCE.getApplicationMetadata();

        puToSchemaMetadata = appMetadata.getSchemaMetadata().getPuToSchemaMetadata();

        // TODO, FIXME: Refactoring is required.
        for (String persistenceUnit : persistenceUnits)
        {
            if (getSchemaProperty(persistenceUnit) != null)
            {
                log.info("Configuring schema export for: " + persistenceUnit);
                List<TableInfo> tableInfos = getSchemaInfo(persistenceUnit);

                Map<Class<?>, EntityMetadata> entityMetadataMap = getEntityMetadataCol(appMetadata, persistenceUnit);

                // Iterate each entity metadata.
                for (EntityMetadata entityMetadata : entityMetadataMap.values())
                {
                    // get entity metadata(table info as well as columns)
                    // if table info exists, get it from map.
                    boolean found = false;
                    Type type = entityMetadata.getType();
//                    Class idClassName = entityMetadata.getIdColumn().getField().getType();
                    Class idClassName = entityMetadata.getIdAttribute().getJavaType();
                    TableInfo tableInfo = new TableInfo(entityMetadata.getTableName(), entityMetadata.isIndexable(),
                            type.name(), idClassName);

                    // check for tableInfos not empty and contains the present
                    // tableInfo.
                    if (!tableInfos.isEmpty() && tableInfos.contains(tableInfo))
                    {
                        found = true;
                        int idx = tableInfos.indexOf(tableInfo);
                        tableInfo = tableInfos.get(idx);
                        addColumnToTableInfo(entityMetadata, type, tableInfo);
                    }
                    else
                    {
                        addColumnToTableInfo(entityMetadata, type, tableInfo);
                    }

                    List<Relation> relations = entityMetadata.getRelations();
                    parseRelations(persistenceUnit, tableInfos, entityMetadata, tableInfo, relations);

                    if (!found)
                    {
                        tableInfos.add(tableInfo);
                    }
                }
                puToSchemaMetadata.put(persistenceUnit, tableInfos);
                ClientResolver.getClientFactory(persistenceUnit).getSchemaManager().exportSchema();
            }
        }
    }

    /**
     * parse the relations of entites .
     * 
     * @param persistenceUnit
     * @param tableInfos
     * @param entityMetadata
     * @param tableInfo
     * @param relations
     */
    private void parseRelations(String persistenceUnit, List<TableInfo> tableInfos, EntityMetadata entityMetadata,
            TableInfo tableInfo, List<Relation> relations)
    {
        for (Relation relation : relations)
        {
            Class entityClass = relation.getTargetEntity();
            EntityMetadata targetEntityMetadata = KunderaMetadataManager.getEntityMetadata(entityClass);
            ForeignKey relationType = relation.getType();

            // if relation type is one to many or join by primary key
            if (relationType.equals(ForeignKey.ONE_TO_MANY) && relation.getJoinColumnName() != null)
            {
                // if self association
                if (targetEntityMetadata.equals(entityMetadata))
                {
                    tableInfo.addColumnInfo(getJoinColumn(relation.getJoinColumnName()));
                }
                else
                {
                    String pu = targetEntityMetadata.getPersistenceUnit();
                    Type targetEntityType = targetEntityMetadata.getType();
//                    Class idClass = targetEntityMetadata.getIdColumn().getField().getType();
                    Class idClass = entityMetadata.getIdAttribute().getJavaType();
                    TableInfo targetTableInfo = new TableInfo(targetEntityMetadata.getTableName(),
                            targetEntityMetadata.isIndexable(), targetEntityType.name(), idClass);

                    // In case of different persistence unit. case for poly glot
                    // persistence.
                    if (!pu.equals(persistenceUnit))
                    {
                        List<TableInfo> targetTableInfos = getSchemaInfo(pu);

                        addJoinColumnToInfo(relation.getJoinColumnName(), targetTableInfo, targetTableInfos);

                        // add for newly discovered persistence unit.
                        puToSchemaMetadata.put(pu, targetTableInfos);
                    }
                    else
                    {
                        addJoinColumnToInfo(relation.getJoinColumnName(), targetTableInfo, tableInfos);
                        // tableInfos.add(targetTableInfo);
                    }
                }
            }
            // if relation type is one to one or many to one.
            else if (relation.isUnary() && relation.getJoinColumnName() != null)
            {
                tableInfo.addColumnInfo(getJoinColumn(relation.getJoinColumnName()));
            }
            // if relation type is many to many and relation via join table.
            else if ((relationType.equals(ForeignKey.MANY_TO_MANY)) && (entityMetadata.isRelationViaJoinTable()))
            {
                String joinTableName = relation.getJoinTableMetadata().getJoinTableName();
                TableInfo joinTableInfo = new TableInfo(joinTableName, false, Type.COLUMN_FAMILY.name(), null);
                if (!tableInfos.isEmpty() && !tableInfos.contains(joinTableInfo) || tableInfos.isEmpty())
                {
                    tableInfos.add(joinTableInfo);
                }
            }
        }
    }

    /**
     * adds join column name to the table Info of entity.
     * 
     * @param joinColumn
     * @param targetTableInfo
     * @param targetTableInfos
     */
    private void addJoinColumnToInfo(String joinColumn, TableInfo targetTableInfo, List<TableInfo> targetTableInfos)
    {
        if (!targetTableInfos.isEmpty() && targetTableInfos.contains(targetTableInfo))
        {
            int idx = targetTableInfos.indexOf(targetTableInfo);
            targetTableInfo = targetTableInfos.get(idx);
            if (!targetTableInfo.getColumnMetadatas().contains(getJoinColumn(joinColumn)))
            {
                targetTableInfo.addColumnInfo(getJoinColumn(joinColumn));
            }
        }
        else
        {
            if (!targetTableInfo.getColumnMetadatas().contains(getJoinColumn(joinColumn)))
            {
                targetTableInfo.addColumnInfo(getJoinColumn(joinColumn));
            }
            targetTableInfos.add(targetTableInfo);
        }

        // targetTableInfo.addColumnInfo(getColumn(entityMetadata.getIdColumn()));
        // targetTableInfos.add(targetTableInfo);
    }

    /**
     * Adds column to table info of entity.
     * 
     * @param entityMetadata
     * @param type
     * @param tableInfo
     */
    private void addColumnToTableInfo(EntityMetadata entityMetadata, Type type, TableInfo tableInfo)
    {
        // Add columns to table info.
        Metamodel metaModel = KunderaMetadata.INSTANCE.getApplicationMetadata().getMetamodel(entityMetadata.getPersistenceUnit());
        EntityType entityType = metaModel.entity(entityMetadata.getEntityClazz());

        Set attributes = entityType.getAttributes();
        
        Iterator<Attribute> iter = attributes.iterator();
        while (iter.hasNext())
        {
            Attribute attr = iter.next();
            if (((MetamodelImpl) metaModel).isEmbeddable(attr.getJavaType()))
            {
                EmbeddableType embeddable = metaModel.embeddable(attr.getJavaType());
                
              EmbeddedColumnInfo embeddedColumnInfo = getEmbeddedColumn(embeddable,attr.getName(),entityMetadata);
              
              if (!tableInfo.getEmbeddedColumnMetadatas().contains(embeddedColumnInfo))
              {
                  tableInfo.addEmbeddedColumnInfo(embeddedColumnInfo);
              }
            }
            else if(!attr.isCollection() && !((SingularAttribute)attr).isId())
            {
                ColumnInfo columnInfo = getColumn(attr, entityMetadata.isColumnIndexable(attr.getName()));
                if (!tableInfo.getColumnMetadatas().contains(columnInfo))
                {
                    tableInfo.addColumnInfo(columnInfo);
                }
            }
        }
    }

    /**
     * Returns list of configured table/column families.
     * 
     * @param persistenceUnit
     *            persistence unit, for which schema needs to be fetched.
     * 
     * @return list of {@link TableInfo}
     */
    private List<TableInfo> getSchemaInfo(String persistenceUnit)
    {
        List<TableInfo> tableInfos = puToSchemaMetadata.get(persistenceUnit);
        // if no TableInfos for given persistence unit.
        if (tableInfos == null)
        {
            tableInfos = new ArrayList<TableInfo>();
        }
        return tableInfos;
    }

    /**
     * Returns map of entity metdata {@link EntityMetadata}.
     * 
     * @param appMetadata
     *            application metadata
     * @param persistenceUnit
     *            persistence unit
     * @return map of entity metadata.
     */
    private Map<Class<?>, EntityMetadata> getEntityMetadataCol(ApplicationMetadata appMetadata, String persistenceUnit)
    {
        Metamodel metaModel = appMetadata.getMetamodel(persistenceUnit);
        Map<Class<?>, EntityMetadata> entityMetadataMap = ((MetamodelImpl) metaModel).getEntityMetadataMap();
        return entityMetadataMap;
    }


    
    private EmbeddedColumnInfo getEmbeddedColumn(EmbeddableType embeddableType, String embeddableColName, EntityMetadata entityMetadata)
    {
        EmbeddedColumnInfo embeddedColumnInfo = new EmbeddedColumnInfo();
        embeddedColumnInfo.setEmbeddedColumnName(embeddableColName);
        List<ColumnInfo> columns = new ArrayList<ColumnInfo>();
        
        Set attributes =  embeddableType.getAttributes();
        Iterator<Attribute> iter = attributes.iterator();
        
        while (iter.hasNext())
        {
            Attribute attr = iter.next();
            columns.add(getColumn(attr, entityMetadata.isColumnIndexable(attr.getName())));
        }
        embeddedColumnInfo.setColumns(columns);
        return embeddedColumnInfo;
    }

    /**
     * getColumn method return ColumnInfo for the given column
     * 
     * @param Object
     *            of Column.
     * @return Object of ColumnInfo.
     */
    private ColumnInfo getColumn(Attribute column, boolean isIndexable)
    {
        ColumnInfo columnInfo = new ColumnInfo();
        columnInfo.setColumnName(((AbstractAttribute)column).getJPAColumnName());
        columnInfo.setIndexable(isIndexable);
        columnInfo.setType(column.getJavaType());
        return columnInfo;
    }

    /**
     * getJoinColumn method return ColumnInfo for the join column
     * 
     * @param String
     *            joinColumnName.
     * @return ColumnInfo object columnInfo.
     */
    private ColumnInfo getJoinColumn(String joinColumnName)
    {
        ColumnInfo columnInfo = new ColumnInfo();
        columnInfo.setColumnName(joinColumnName);
        columnInfo.setIndexable(true);
        return columnInfo;
    }

    /**
     * getKunderaProperty method return auto schema generation property for give
     * persistence unit.
     * 
     * @param String
     *            persistenceUnit.
     * @return value of kundera auto ddl in form of String.
     */
    private String getSchemaProperty(String persistenceUnit)
    {
        String KUNDERA_DDL_AUTO_PREPARE = KunderaMetadata.INSTANCE.getApplicationMetadata()
                .getPersistenceUnitMetadata(persistenceUnit)
                .getProperty(PersistenceProperties.KUNDERA_DDL_AUTO_PREPARE);

        return KUNDERA_DDL_AUTO_PREPARE;
    }
}