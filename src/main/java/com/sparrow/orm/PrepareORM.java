/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sparrow.orm;

import com.sparrow.cg.MethodAccessor;
import com.sparrow.constant.CONSTANT;
import com.sparrow.constant.magic.SYMBOL;
import com.sparrow.container.ClassFactoryBean;
import com.sparrow.container.Container;
import com.sparrow.core.StrategyFactory;
import com.sparrow.core.spi.ApplicationContext;
import com.sparrow.enums.STATUS_RECORD;
import com.sparrow.orm.query.SearchCriteria;
import com.sparrow.orm.query.UpdateCriteria;
import com.sparrow.orm.query.sql.OperationEntity;
import com.sparrow.orm.query.sql.CriteriaProcessor;
import com.sparrow.orm.type.JdbcType;
import com.sparrow.orm.type.TypeHandler;
import com.sparrow.orm.type.TypeHandlerRegistry;
import com.sparrow.utility.StringUtility;

import java.lang.reflect.InvocationTargetException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author harry
 */
public class PrepareORM<T> {
    private static Logger logger = LoggerFactory.getLogger(PrepareORM.class);

    private CriteriaProcessor criteriaProcessor;
    /**
     * 实体类
     */
    private Class<?> modelClazz = null;

    private String modelName = null;

    private Container container = ApplicationContext.getContainer();

    ClassFactoryBean<EntityManager> entityManagerFactoryBean = EntityManagerFactoryBean.getInstance();

    private SparrowEntityManager entityManager = null;
    /**
     * 方法访问对象
     */
    private MethodAccessor methodAccessor = null;

    public MethodAccessor getMethodAccessor() {
        return methodAccessor;
    }

    public SparrowEntityManager getEntityManager() {
        return entityManager;
    }

    public Container getContainer() {
        return container;
    }

    public Class<?> getModelClazz() {
        return modelClazz;
    }

    public String getTableName(List<Object> tableSuffix) {
        return this.getEntityManager().getTableName() + this.getEntityManager().getTableSuffix(tableSuffix);
    }

    /**
     * JORM.java的构造函数
     */
    public PrepareORM(Class modelClazz, CriteriaProcessor criteriaProcessor) {
        this.modelClazz = modelClazz;
        this.methodAccessor = container.getProxyBean(
                this.modelClazz);
        this.entityManager = new SparrowEntityManager(this.modelClazz);
        this.entityManagerFactoryBean.pubObject(this.modelClazz, this.entityManager);
        this.modelName = StringUtility.getEntityNameByClass(this.modelClazz);
        this.criteriaProcessor = criteriaProcessor;
    }

    public JDBCParameter insert(T model) {
        String insertSQL = this.entityManager.getInsert();
        boolean isIncrement = false;
        List<Parameter> parameters = new ArrayList<Parameter>();
        Map<Integer, Object> tableSuffix = new TreeMap<Integer, Object>();
        for (Field field : this.entityManager.getFieldMap().values()) {
            Object o = this.methodAccessor.get(model, field.getName());
            if (field.getGenerationType() == null) {
                this.entityManager.parseField(field, parameters, o, tableSuffix, false);
                continue;
            }
            switch (field.getGenerationType()) {
                case TABLE:
                case SEQUENCE:
                    String key = field.getGenerator();
                    //todo 根据指定表的KEY生成id
                    break;
                case AUTO:
                    String generator = field.getGenerator();
                    String id;
                    //预先设定的的主键
                    if (StringUtility.isNullOrEmpty(generator) || "set".equalsIgnoreCase(generator)) {
                        parameters.add(new Parameter(field, o));
                    } else {
                        //uuid IDGeneratorImpl
                        IDGenerator idGenerator = StrategyFactory.getInstance().get(IDGenerator.class, generator);
                        if (idGenerator != null) {
                            id = idGenerator.generate();
                            parameters.add(new Parameter(field, id));
                            this.methodAccessor
                                    .set(model, field.getName(), id);
                        }
                    }
                    break;
                case IDENTITY:
                    isIncrement = true;
                    break;
                default:
            }
        }
        if (tableSuffix.size() > 0) {
            if (insertSQL.contains(CONSTANT.TABLE_SUFFIX)) {
                insertSQL = insertSQL.replace(CONSTANT.TABLE_SUFFIX, this.entityManager.getTableSuffix(tableSuffix));
            }
        }
        return new JDBCParameter(insertSQL, parameters, isIncrement);
    }

    public JDBCParameter update(T model) {
        String updateSQL = this.entityManager.getUpdate();
        List<Parameter> parameters = new ArrayList<Parameter>();
        Parameter whereParameter = null;
        Map<Integer, Object> tableSuffix = new TreeMap<Integer, Object>();
        for (Field field : this.entityManager.getFieldMap().values()) {
            Object o = this.methodAccessor.get(model, field.getName());
            if (field.isPrimary()) {
                whereParameter = new Parameter(field, o);
            } else {
                this.entityManager.parseField(field, parameters, o, tableSuffix, true);
            }
        }
        if (tableSuffix.size() > 0) {
            if (updateSQL.contains(CONSTANT.TABLE_SUFFIX)) {
                updateSQL = updateSQL.replace(CONSTANT.TABLE_SUFFIX, this.entityManager.getTableSuffix(tableSuffix));
            }
        }
        parameters.add(whereParameter);
        return new JDBCParameter(updateSQL, parameters, false);
    }

    public JDBCParameter update(UpdateCriteria criteria) {
        OperationEntity where = this.criteriaProcessor.where(criteria.getWhere());
        OperationEntity setClause = this.criteriaProcessor.setClause(criteria.getSetClausePairList());
        String update = String.format("update %1$s set %2$s where %3$s",
                this.getTableName(criteria.getTableSuffix()), setClause.getClause(),
                where.getClause());
        List<Parameter> updateParameters = new ArrayList<Parameter>();
        updateParameters.addAll(setClause.getParameterList());
        updateParameters.addAll(where.getParameterList());
        return new JDBCParameter(update, updateParameters);
    }

    public JDBCParameter delete(Object id) {
        Field primaryField = this.entityManager.getPrimary();
        return new JDBCParameter(this.entityManager.getDelete(),
                Collections.singletonList(new Parameter(primaryField, primaryField.convert(id.toString()))));
    }

    public JDBCParameter delete(SearchCriteria searchCriteria) {
        String delete = String.format("DELETE FROM %1$s", this.getTableName(searchCriteria.getTableSuffix()));
        OperationEntity where = this.criteriaProcessor.where(searchCriteria.getWhere());
        if (!StringUtility.isNullOrEmpty(where.getClause())) {
            delete += " WHERE " + where.getClause();
        }
        return new JDBCParameter(delete, where.getParameterList());
    }

    public T setEntity(
            Map<String, Object> values) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException, InstantiationException {
        if (values == null || values.size() == 0) {
            return null;
        }

        T model = (T) this.modelClazz.getConstructor().newInstance();
        for (Field field : this.entityManager.getFieldMap().values()) {
            if (field == null) {
                continue;
            }
            try {
                if (!field.isPersistence()) {
                    continue;
                }
                if (values.containsKey(field.getColumnName())) {
                    this.methodAccessor.set(model, field.getName(),
                            values.get(field.getColumnName()));
                }
            } catch (Exception e) {
                logger.error("set entity error", e);
            }
        }
        return model;
    }

    public T setEntity(ResultSet rs, ResultSetCallback resultSetCallback) {
        try {
            ResultSetMetaData resultSetMetaData = rs.getMetaData();
            T model = (T) this.modelClazz.getConstructor().newInstance();

            TypeHandlerRegistry typeHandlerRegistry = TypeHandlerRegistry.getInstance();

            for (int i = 1; i <= resultSetMetaData.getColumnCount(); i++) {
                String fieldName = resultSetMetaData.getColumnName(i);
                Class javaType = this.entityManager.getFieldMap().get(fieldName).getType();
                JdbcType jdbcType = JdbcType.forCode(resultSetMetaData.getColumnType(i));
                TypeHandler typeHandler = typeHandlerRegistry.getTypeHandler(javaType, jdbcType);
                Object fieldValue = typeHandler.getResult(rs, i);
                try {
                    this.getMethodAccessor().set(model, this.getEntityManager().getProperty(fieldName), fieldValue);
                } catch (Exception e) {
                    logger.error(this.modelClazz.getSimpleName() + SYMBOL.VERTICAL_LINE + fieldName, e);
                }
            }
            return model;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (resultSetCallback != null) {
                resultSetCallback.close(rs);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public JDBCParameter getEntity(Object key, String uniqueKey) {
        StringBuilder select = new StringBuilder("select ");
        select.append(this.entityManager.getFields());
        select.append(" from "
                + this.entityManager.getTableName() + " as " + this.modelName);
        select.append(" where " + this.entityManager.getPrimary().getColumnName() + "=?");
        return new JDBCParameter(select.toString(), Arrays.asList(new Parameter(this.entityManager.getUniqueField(uniqueKey), key)));
    }

    public JDBCParameter getCount(Object key, String uniqueKey) {
        StringBuilder select = new StringBuilder("select count(*) from "
                + this.entityManager.getTableName() + " as " + this.modelName);
        Field uniqueField = this.entityManager.getUniqueField(uniqueKey);
        select.append(" where "
                + uniqueField.getColumnName() + "=?");
        return new JDBCParameter(select.toString(), Arrays.asList(new Parameter(uniqueField, key)));
    }

    public JDBCParameter getCount(SearchCriteria criteria) {
        OperationEntity boolOperationEntity = this.criteriaProcessor.where(criteria.getWhere());
        String field = this.criteriaProcessor.fields(criteria.getFields());
        String whereClause = boolOperationEntity.getClause().toString();
        String tableName = null;
        if (criteria.getDistinct()) {
            field = "distinct " + field;
        }
        StringBuilder select = new StringBuilder();

        tableName = this.getTableName(criteria.getTableSuffix()) + " as "
                + this.modelName;
        select.append(String.format("select count(%1$s) from %2$s",
                field, tableName));
        if (!StringUtility.isNullOrEmpty(whereClause)) {
            select.append(" where " + whereClause);
        }
        return new JDBCParameter(select.toString(), boolOperationEntity.getParameterList());
    }

    public JDBCParameter getFieldValue(String fieldName, Object key, String uniqueKey) {
        if (fieldName.contains(SYMBOL.COMMA)) {
            fieldName = fieldName.split("\\.")[1];
        }
        fieldName = this.entityManager.getField(fieldName).getColumnName();
        String select = String.format("select %1$s from %2$s", fieldName,
                this.entityManager.getTableName() + " as "
                        + this.modelName);
        if (uniqueKey != null) {
            Field uniqueField = this.entityManager.getUniqueField(uniqueKey);
            select += String.format(" where %1$s=?", uniqueField.getColumnName());
            return new JDBCParameter(select, Collections.singletonList(new Parameter(uniqueField, key)));
        } else {
            return new JDBCParameter(select);
        }
    }

    public JDBCParameter changeStatus(String primaryKey, STATUS_RECORD status) {
        String whereClause = null;
        if (primaryKey.contains(SYMBOL.COMMA)) {
            whereClause = " in(" + primaryKey + SYMBOL.RIGHT_PARENTHESIS;
        } else {
            whereClause = " =?";
        }
        String updateSql = String.format("update %1$s set %2$s=? where %3$s %4$s",
                this.entityManager.getTableName(),
                this.entityManager.getStatus().getColumnName(),
                this.entityManager.getPrimary().getColumnName(),
                whereClause);

        Parameter[] sqlParameters;
        if (primaryKey.contains(SYMBOL.COMMA)) {
            sqlParameters = new Parameter[]{
                    new Parameter(this.entityManager.getStatus(), status.name())};
        } else {
            sqlParameters = new Parameter[]{
                    new Parameter(this.entityManager.getStatus(), status.name()),
                    new Parameter(this.entityManager.getPrimary(), this.entityManager.getPrimary().convert(primaryKey))};
        }
        return new JDBCParameter(updateSql, Arrays.asList(sqlParameters));
    }

    public JDBCParameter batchDelete(String ids) {
        String whereClause;
        if (ids.contains(SYMBOL.COMMA)) {
            whereClause = " in(" + ids + SYMBOL.RIGHT_PARENTHESIS;
        } else {
            whereClause = " =?";
        }
        String updateSql = String.format("DELETE FROM %1$s where %2$s %3$s",
                this.entityManager.getTableName(),
                this.entityManager.getPrimary().getColumnName(),
                whereClause);

        Parameter[] sqlParameters = new Parameter[0];
        if (!ids.contains(SYMBOL.COMMA)) {
            sqlParameters = new Parameter[]{
                    new Parameter(this.entityManager.getPrimary(),
                            this.entityManager.getPrimary().convert(ids))
            };
        }
        return new JDBCParameter(updateSql, Arrays.asList(sqlParameters));
    }
}