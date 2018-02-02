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

package com.sparrow.orm.template.impl;

import com.sparrow.constant.CONFIG_KEY_DB;
import com.sparrow.constant.SYS_OBJECT_NAME;
import com.sparrow.constant.magic.DIGIT;
import com.sparrow.core.Pair;
import com.sparrow.core.spi.ApplicationContext;
import com.sparrow.enums.DATABASE_SPLIT_STRATEGY;
import com.sparrow.enums.STATUS_RECORD;
import com.sparrow.orm.Field;
import com.sparrow.orm.JDBCParameter;
import com.sparrow.orm.JDBCTemplate;
import com.sparrow.orm.Parameter;
import com.sparrow.orm.PrepareORM;
import com.sparrow.orm.query.AGGREGATE;
import com.sparrow.orm.query.SearchCriteria;
import com.sparrow.orm.query.UpdateCriteria;
import com.sparrow.orm.query.sql.CriteriaProcessor;
import com.sparrow.orm.query.sql.OperationEntity;
import com.sparrow.orm.query.sql.impl.criteria.processor.SqlCriteriaProcessorImpl;
import com.sparrow.orm.template.SparrowDaoSupport;
import com.sparrow.support.ConnectionContextHolder;
import com.sparrow.support.db.JDBCSupport;
import com.sparrow.utility.StringUtility;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author harry
 */
public class DBORMTemplate<T, I> implements SparrowDaoSupport<T, I> {
    private static Logger logger = LoggerFactory.getLogger(PrepareORM.class);

    protected CriteriaProcessor criteriaProcessor = new SqlCriteriaProcessorImpl();
    /**
     * 实体类
     */
    private Class<?> modelClazz = null;

    private String modelName = null;
    /**
     * 数据库辅助对象
     */
    protected final JDBCSupport jdbcSupport;

    private PrepareORM<T> prepareORM;

    public DBORMTemplate(Class clazz) {
        this.modelClazz = clazz;
        if (this.modelClazz != null) {
            this.modelName = StringUtility.getEntityNameByClass(this.modelClazz);
        }
        this.prepareORM = new PrepareORM<T>(this.modelClazz, this.criteriaProcessor);
        DATABASE_SPLIT_STRATEGY databaseSplitKey = this.prepareORM.getEntityManager().getDatabaseSplitStrategy();
        this.jdbcSupport = JDBCTemplate.getInstance(this.prepareORM.getEntityManager().getSchema(), databaseSplitKey);
    }

    @Override
    public Long insert(T model) {
        try {
            JDBCParameter jdbcParameter = this.prepareORM.insert(model);
            if (jdbcParameter.isAutoIncrement()) {
                Long id = this.jdbcSupport.executeAutoIncrementInsert(jdbcParameter);
                this.prepareORM.getMethodAccessor().set(model, this.prepareORM.getEntityManager().getPrimary().getName(), id);
                return id;
            } else {
                this.jdbcSupport.executeUpdate(jdbcParameter);
                return 0L;
            }
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public int update(T model) {
        return this.jdbcSupport.executeUpdate(this.prepareORM.update(model));
    }

    @Override
    public int update(UpdateCriteria criteria) {
        return this.jdbcSupport.executeUpdate(this.prepareORM.update(criteria));
    }

    @Override
    public int delete(I id) {
        return this.jdbcSupport.executeUpdate(this.prepareORM.delete(id));
    }

    @Override
    public int delete(SearchCriteria criteria) {
        return this.jdbcSupport.executeUpdate(this.prepareORM.delete(criteria));
    }

    @Override
    public int batchDelete(String ids) {
        JDBCParameter parameter = this.prepareORM.batchDelete(ids);
        return this.jdbcSupport.executeUpdate(parameter);
    }

    private JDBCParameter getSelectSql(AGGREGATE aggregate, SearchCriteria searchCriteria) {
        StringBuilder selectSql = new StringBuilder();
        OperationEntity boolOperationEntity = this.criteriaProcessor.where(searchCriteria.getWhere());
        String whereClause = boolOperationEntity.getClause().toString();
        String orderClause = this.criteriaProcessor.order(searchCriteria.getOrderCriteriaList());
        selectSql.append("select ");

        if (aggregate == null) {
            String fields = this.criteriaProcessor.fields(searchCriteria.getFields());
            if (searchCriteria.getDistinct()) {
                selectSql.append(" distinct ");
            }
            selectSql.append(fields);
        } else {
            String columns = this.criteriaProcessor.aggregate(aggregate, searchCriteria.getFields());
            selectSql.append(columns);
        }
        selectSql.append(" from " + this.prepareORM.getTableName(searchCriteria.getTableSuffix())
            + " as " + StringUtility.getEntityNameByClass(this.modelClazz));
        if (!StringUtility.isNullOrEmpty(whereClause)) {
            selectSql.append(" where " + whereClause);
        }

        if (!StringUtility.isNullOrEmpty(orderClause)) {
            selectSql.append(" order by " + orderClause);
        }

        if (!StringUtility.isNullOrEmpty(searchCriteria.getPageSize())
            && searchCriteria.getPageSize() != DIGIT.ALL) {
            selectSql.append(searchCriteria.getLimitClause());
        }
        logger.info(selectSql.toString());
        return new JDBCParameter(selectSql.toString(), boolOperationEntity.getParameterList());
    }

    private ORMResult select(SearchCriteria searchCriteria) {
        Long count = this.getCount(searchCriteria);
        if (count == 0) {
            return null;
        }
        JDBCParameter jdbcParameter = this.getSelectSql(null, searchCriteria);
        ResultSet rs = this.jdbcSupport.executeQuery(jdbcParameter);
        return new ORMResult(rs, count);
    }

    @Override
    public T getEntity(Object id) {
        return this.getEntity(id, CONFIG_KEY_DB.ORM_PRIMARY_KEY_UNIQUE);
    }

    @Override
    public T getEntity(Object key, String uniqueKey) {
        StringBuilder select = new StringBuilder("select ");
        select.append(this.prepareORM.getEntityManager().getFields());
        select.append(" from "
            + this.prepareORM.getEntityManager().getTableName());
        select.append(" " + this.modelName);
        Field uniqueField = this.prepareORM.getEntityManager().getUniqueField(uniqueKey);
        select.append(" where " + uniqueField.getColumnName() + "=?");
        JDBCParameter jdbcParameter = new JDBCParameter(select.toString(), Collections.singletonList(new Parameter(uniqueField, uniqueField.convert(key.toString()))));
        ResultSet rs = this.jdbcSupport.executeQuery(jdbcParameter);

        if (rs == null) {
            return null;
        }
        T t = null;
        try {
            if (rs.next()) {
                t = this.prepareORM.setEntity(rs, null);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            this.jdbcSupport.release(rs);
        }
        return t;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T getEntity(SearchCriteria criteria) {
        ORMResult ormResult = this.select(criteria);
        if (ormResult == null) {
            return null;
        }
        ResultSet rs = ormResult.getResultSet();
        T model = null;
        try {
            if (rs.next()) {
                if (criteria != null && criteria.getRowMapper() != null) {
                    model = (T) criteria.getRowMapper().mapRow(rs, rs.getRow());
                } else {
                    model = this.prepareORM.setEntity(rs, null);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            jdbcSupport.release(rs);
        }
        return model;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<T> getList(SearchCriteria criteria) {
        //返回null会报错
        List<T> list;
        if (criteria != null && criteria.getPageSize() > 0) {
            list = new ArrayList<T>(criteria.getPageSize());
        } else {
            list = new ArrayList<T>();
        }

        ORMResult ormResult = this.select(criteria);
        if (ormResult == null) {
            return list;
        }

        ResultSet rs = ormResult.getResultSet();
        try {
            while (rs.next()) {
                T m;
                if (criteria != null && criteria.getRowMapper() != null) {
                    m = (T) criteria.getRowMapper().mapRow(rs, rs.getRow());
                } else {
                    m = this.prepareORM.setEntity(rs, null);
                }
                list.add(m);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            this.jdbcSupport.release(ormResult.getResultSet());
        }
        return list;
    }

    @Override
    public <Z> Set<Z> firstList(SearchCriteria criteria) {
        Set<Z> list = new LinkedHashSet<Z>();
        ORMResult ormResult = this.select(criteria);
        if (ormResult == null || ormResult.getResultSet() == null || ormResult.getRecordCount() == 0) {
            return list;
        }

        try {
            ResultSet rs = ormResult.getResultSet();
            while (rs.next()) {
                Object o = rs.getObject(1);
                if (o instanceof Number) {
                    list.add((Z) o);
                } else {
                    list.add((Z) ("'" + o.toString().trim() + "'"));
                }
            }
        } catch (Exception ex) {
            logger.error("first result", ex);
            return list;
        } finally {
            this.jdbcSupport.release(ormResult.getResultSet());
        }
        return list;
    }

    @Override
    public <P> P scalar(SearchCriteria criteria) {
        return (P) this.jdbcSupport.executeScalar(this.getSelectSql(null, criteria));
    }

    @Override
    public List<T> getList() {
        return this.getList(null);
    }

    @Override
    public <P, Q> Map<P, Q> getMap(SearchCriteria searchCriteria) {
        Map<P, Q> map = new LinkedHashMap<P, Q>();
        ORMResult ormResult = this.select(searchCriteria);
        if (ormResult == null) {
            return map;
        }
        try {
            ResultSet rs = ormResult.getResultSet();
            while (rs.next()) {
                if (searchCriteria.getRowMapper() != null) {
                    Pair<P, Q> entry = (Pair<P, Q>) searchCriteria.getRowMapper().mapRow(rs, rs.getRow());
                    map.put(entry.getFirst(), entry.getSecond());
                } else {
                    String fields = this.criteriaProcessor.fields(searchCriteria.getFields());
                    Pair<String, String> fieldPair = Pair.split(fields, ",");
                    map.put((P) rs.getObject(fieldPair.getFirst()), (Q) rs.getObject(fieldPair.getSecond()));
                }
            }
            return map;
        } catch (Exception ex) {
            logger.error("get map", ex);
            return map;
        } finally {
            this.jdbcSupport.release(ormResult.getResultSet());
        }
    }

    @Override
    public Long getCount(Object key, String uniqueKey) {
        JDBCParameter jdbcParameter = this.prepareORM.getCount(key, uniqueKey);
        Object count = this.jdbcSupport.executeScalar(jdbcParameter);
        if (count == null) {
            return 0L;
        } else {
            return Long.valueOf(count.toString());
        }
    }

    @Override
    public Long getCount(Object key) {
        return this.getCount(key, CONFIG_KEY_DB.ORM_PRIMARY_KEY_UNIQUE);
    }

    @Override
    public Long getCount(SearchCriteria criteria) {
        JDBCParameter jdbcParameter = this.prepareORM.getCount(criteria);
        Long count = this.jdbcSupport.executeScalar(jdbcParameter);
        if (count == null) {
            return 0L;
        } else {
            return count;
        }
    }

    @Override
    public <X> X getAggregate(AGGREGATE aggregate, SearchCriteria searchCriteria) {
        JDBCParameter jdbcParameter = this.getSelectSql(aggregate, searchCriteria);
        Object fieldValue = this.jdbcSupport.executeScalar(jdbcParameter);
        if (fieldValue == null) {
            return null;
        } else {
            return (X) fieldValue;
        }
    }

    @Override
    public <X> X getAggregate(String fieldName, AGGREGATE aggregate) {
        SearchCriteria searchCriteria = new SearchCriteria();
        searchCriteria.setFields(fieldName);
        return this.getAggregate(aggregate, searchCriteria);
    }

    @Override
    public <X> X getFieldValue(String fieldName, Object key) {
        return this.getFieldValue(fieldName, key,
            CONFIG_KEY_DB.ORM_PRIMARY_KEY_UNIQUE);
    }

    @Override
    public <X> X getFieldValue(String fieldName, Object key, String uniqueKey) {
        JDBCParameter jdbcParameter = this.prepareORM.getFieldValue(fieldName, key, uniqueKey);
        Object fieldValue = this.jdbcSupport.executeScalar(jdbcParameter);
        if (fieldValue == null) {
            return null;
        } else {
            return (X) fieldValue;
        }
    }

    @Override
    public <X> X getFieldValue(SearchCriteria searchCriteria) {
        JDBCParameter jdbcParameter = this.getSelectSql(null, searchCriteria);
        Object fieldValue = this.jdbcSupport.executeScalar(jdbcParameter);
        if (fieldValue == null) {
            return null;
        } else {
            return (X) fieldValue;
        }
    }

    @Override
    public int changeStatus(String primaryKey, STATUS_RECORD status) {
        JDBCParameter jdbcParameter = this.prepareORM.changeStatus(primaryKey, status);
        return this.jdbcSupport.executeUpdate(jdbcParameter);
    }

    class ORMResult {
        ORMResult() {
        }

        public ORMResult(ResultSet resultSet, Long recordCount) {
            this.resultSet = resultSet;
            this.recordCount = recordCount;
        }

        public void setResultSet(ResultSet resultSet) {
            this.resultSet = resultSet;
        }

        public void setRecordCount(Long recordCount) {
            this.recordCount = recordCount;
        }

        public ResultSet getResultSet() {
            return resultSet;
        }

        public Long getRecordCount() {
            return recordCount;
        }

        ResultSet resultSet;
        Long recordCount;
    }
}