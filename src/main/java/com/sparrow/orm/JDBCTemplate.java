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

import com.sparrow.constant.CONSTANT;
import com.sparrow.constant.magic.SYMBOL;
import com.sparrow.datasource.DataSourceFactory;
import com.sparrow.datasource.DatasourceKey;
import com.sparrow.enums.DATABASE_SPLIT_STRATEGY;
import com.sparrow.enums.STATUS_RECORD;
import com.sparrow.support.ContextHolder;
import com.sparrow.support.db.JDBCSupport;
import com.sparrow.utility.StringUtility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;

/**
 * 兼顾事务异常必须全部抛出
 *
 * @author harry
 */
public class JDBCTemplate implements JDBCSupport {
    Logger logger = LoggerFactory.getLogger(JDBCTemplate.class);
    /**
     * *********************************** 变量定义 *******************************************************************
     */
    private static Map<String, JDBCSupport> executorPool = new ConcurrentHashMap<String, JDBCSupport>();

    /**
     * dataSource与dataSourceSplitStrategy 两者唯一标识一个template 实际上是可以唯一确定一个数据源
     */
    private String schema;
    private DATABASE_SPLIT_STRATEGY dataSourceSplitStrategy;

    private DataSourceFactory dataSourceFactory = DataSourceFactory.getInstance();
    /**
     * 连接支持器
     */
    private ContextHolder connectionHolder = ContextHolder.getInstance();

    /**
     * @return
     */
    public static JDBCSupport getInstance(String schema, DATABASE_SPLIT_STRATEGY databaseSplitStrategy) {
        if (databaseSplitStrategy == null) {
            databaseSplitStrategy = DATABASE_SPLIT_STRATEGY.DEFAULT;
        }
        if (StringUtility.isNullOrEmpty(schema)) {
            schema = DatasourceKey.getDefault().getSchema();
        }
        String jdbcIdentify = schema + "_" + databaseSplitStrategy.toString().toLowerCase();
        if (schema != null && executorPool.containsKey(jdbcIdentify)) {
            return executorPool.get(jdbcIdentify);
        } else {
            JDBCSupport jdbcSupport = new JDBCTemplate(schema, databaseSplitStrategy);
            executorPool.put(jdbcIdentify, jdbcSupport);
            return jdbcSupport;
        }
    }

    public static JDBCSupport getInstance() {
        return getInstance(null, null);
    }

    private JDBCTemplate(String schema, DATABASE_SPLIT_STRATEGY databaseSplitStrategy) {
        if (StringUtility.isNullOrEmpty(schema)) {
            schema = DatasourceKey.getDefault().getSchema();
        }
        this.schema = schema;
        this.dataSourceSplitStrategy = databaseSplitStrategy;
    }

    /************************************* 执行基础SQL调用 参数与存储过程 ***************************************************/

    /**
     * 设置参数
     * <p/>
     * 参数类型未知的情况下调用
     * <p/>
     * 仅提供给ORM 新增更新时，ORM反射未知数据类型时使用
     *
     * @param preparedStatement
     * @param parameter
     * @param index
     */
    private void bindParameter(PreparedStatement preparedStatement,
        Parameter parameter, int index) {

        Object value = parameter.getParameterValue();
        Class<?> fieldType = parameter.getType();
        if (fieldType == null && value != null) {
            fieldType = value.getClass();
        }
        try {
            if (fieldType == int.class || fieldType == Integer.class) {
                preparedStatement.setInt(
                    index,
                    StringUtility.isNullOrEmpty(value) ? 0 : (Integer) value);
            } else if (fieldType == long.class || fieldType == Long.class) {
                preparedStatement.setLong(
                    index,
                    StringUtility.isNullOrEmpty(value) ? 0L : (Long) value);
            } else if (fieldType == String.class) {
                preparedStatement.setString(index, StringUtility
                    .isNullOrEmpty(value) ? "" : String.valueOf(value));
            } else if (fieldType == Date.class) {
                preparedStatement
                    .setDate(
                        index, (Date) value);
            } else if (fieldType == Timestamp.class) {
                preparedStatement
                    .setTimestamp(
                        index, (Timestamp) value);
            } else if (fieldType == boolean.class || fieldType == Boolean.class) {
                boolean b = false;
                if (!StringUtility.isNullOrEmpty(value)) {
                    if (String.valueOf(STATUS_RECORD.ENABLE
                        .ordinal()).equals(value) || Boolean.TRUE.toString().equalsIgnoreCase(value.toString())) {
                        b = true;
                    }
                }
                preparedStatement.setBoolean(
                    index, b);
            } else if (fieldType == double.class || fieldType == Double.class) {
                preparedStatement.setDouble(
                    index,
                    StringUtility.isNullOrEmpty(value) ? 0.0 : (Double) value);
            } else if (fieldType == BigDecimal.class) {
                BigDecimal bigDecimal = (BigDecimal) value;
                if (bigDecimal == null) {
                    bigDecimal = new BigDecimal(0);
                }
                bigDecimal = bigDecimal.setScale(parameter.getScale(), BigDecimal.ROUND_HALF_UP);
                preparedStatement.setBigDecimal(
                    index, bigDecimal);
            } else {
                preparedStatement.setObject(index, null);
                logger.debug("JDBCTemplate setSQLParameter error sqlType not exist"
                    + fieldType);
            }
        } catch (Exception e) {
            logger.error(
                "Executor JDBCTemplate error attribute:"
                    + parameter.getName() + " value:" + value
                    + " type:" + fieldType, e);
            throw new RuntimeException(e);
        }
    }

    private String getDataSourceSuffix() {
        String suffix = null;
        switch (this.dataSourceSplitStrategy) {
            case LANGUAGE:
                suffix = (String) ContextHolder.getInstance().get(CONSTANT.REQUEST_LANGUAGE);
                break;
            case USER_ID:
                suffix = (String) ContextHolder.getInstance().get(CONSTANT.REQUEST_USER_ID);
                break;
            case FOREIGN_KEY:
            case USER_DEFINED:
                suffix = (String) ContextHolder.getInstance().get(CONSTANT.REQUEST_DATABASE_SUFFIX);
                break;
            default:
                suffix = "default";
                break;
        }
        return suffix;
    }

    /**
     * 获取数据库连接
     *
     * @return
     */
    private synchronized Connection getConnection() {
        //todo data source key 与 connection url不一致
        //todo data source+suffix determine datasource
        //todo data source+database_split_key determine jdbc template
        // todo determine object
        DatasourceKey dataSourceKey = new DatasourceKey(this.schema, this.getDataSourceSuffix());
        Connection connection = this.connectionHolder.getConnection(dataSourceKey.getKey());
        //当前未绑定链接或已经绑定但不是事务
        try {
            if (connection == null || connection.getAutoCommit()) {
                // 新连接并与当前线程绑定
                DataSource dataSource = dataSourceFactory.getDataSource(dataSourceKey.getKey());
                connection = dataSource.getConnection();
                //不管是否为事务都需要绑定到线程上，以便执行完后关闭proxyConnection
                //(ProxyConnection)connection会报错，故getConnection之后无法放回池中
                this.connectionHolder
                    .bindConnection(connection);
            }
        } catch (SQLException e) {
            logger.error("get connection error", e);
        }
        return connection;
    }

    /**
     * 获取PreparedStatement对象用于参数化SQL的执行
     *
     * @param jdbcParameter
     * @return
     * @throws Exception
     */
    private PreparedStatement getPreparedStatement(JDBCParameter jdbcParameter) {
        PreparedStatement preparedStatement = null;
        Connection connection = null;
        try {
            connection = this.getConnection();
            connection.setReadOnly(jdbcParameter.isReadOnly());
            if (jdbcParameter.isAutoIncrement()) {
                preparedStatement = connection.prepareStatement(jdbcParameter.getCommand(),
                    Statement.RETURN_GENERATED_KEYS);
            } else {
                // 存储过程
                if (jdbcParameter.getCommand().trim().toLowerCase().startsWith("call")) {
                    if (jdbcParameter.isReadOnly()) {
                        preparedStatement = connection.prepareCall(jdbcParameter.getCommand(),
                            ResultSet.TYPE_SCROLL_INSENSITIVE,
                            ResultSet.CONCUR_READ_ONLY);
                    } else {
                        preparedStatement = connection.prepareCall(jdbcParameter.getCommand());
                    }
                } else {
                    if (jdbcParameter.isReadOnly()) {
                        preparedStatement = connection.prepareStatement(jdbcParameter.getCommand(),
                            ResultSet.TYPE_SCROLL_INSENSITIVE,
                            ResultSet.CONCUR_READ_ONLY);
                    } else {
                        preparedStatement = connection.prepareStatement(jdbcParameter.getCommand());
                    }
                }
            }
            for (int i = 0; i < jdbcParameter.getParameters().size(); i++) {
                this.bindParameter(preparedStatement, jdbcParameter.getParameters().get(i), i + 1);
            }
            return preparedStatement;
        } catch (Exception e) {
            if (connection != null) {
                //自动提交，非事务
                Boolean autoCommit = false;
                try {
                    autoCommit = connection.getAutoCommit();
                } catch (SQLException e1) {
                    e1.printStackTrace();
                }
                if (!autoCommit) {
                    this.connectionHolder.unbindConnection(connection);
                } else {
                    //如果是事务则抛出异常 rollback
                    throw new RuntimeException(e);
                }
            }
            logger.error("connection error", e);
            return null;
        } finally {
            String commandString = jdbcParameter.getCommand();
            for (Parameter parameter : jdbcParameter.getParameters()) {
                Object parameterValue = parameter.getParameterValue();
                if (parameterValue == null) {
                    parameterValue = SYMBOL.EMPTY;
                }
                commandString = commandString.replaceFirst("\\?",
                    Matcher.quoteReplacement(parameterValue.toString()));
            }
            logger.debug("SQL:" + commandString);
        }
    }

    /*************************************** 执行更新操作(增删改) ***********************************************************/
    /**
     * 执行多条更新语句
     *
     * @param commandString
     * @throws Exception
     */
    @Override
    public void executeUpdate(String[] commandString) {
        Statement statement = null;
        try {
            statement = this.getConnection().createStatement();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        for (String command : commandString) {
            try {
                statement.addBatch(command);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            logger.debug("BATCH SQL:" + command);
        }
        try {
            statement.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            this.release(statement);
        }
    }

    /**
     * 执行一条非参数化的更新语句.
     *
     * @param commandString
     * @throws Exception
     */
    @Override
    public int executeUpdate(String commandString) {
        Connection connection = this.getConnection();
        Statement statement = null;
        try {
            statement = connection.createStatement();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        try {
            return statement.executeUpdate(commandString);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            logger.debug("SQL:" + commandString);
            this.release(statement);
        }
    }

    /**
     * 执行一条参数化的更新语句
     *
     * @param jdbcParameter
     * @return
     * @throws Exception
     */
    @Override
    public int executeUpdate(JDBCParameter jdbcParameter) {
        PreparedStatement preparedStatement = this.getPreparedStatement(jdbcParameter);
        if (preparedStatement != null) {
            try {
                try {
                    return preparedStatement.executeUpdate();
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            } finally {
                this.release(preparedStatement);
            }
        }
        return 0;
    }

    /**
     * 执行自增插入 (非事务)
     *
     * @param jdbcParameter
     * @return
     * @throws Exception
     */
    @Override
    public Long executeAutoIncrementInsert(JDBCParameter jdbcParameter) {
        Long generatedKey = 0L;
        PreparedStatement preparedStatement = this.getPreparedStatement(jdbcParameter);
        try {
            preparedStatement.executeUpdate();
            ResultSet result = preparedStatement.getGeneratedKeys();
            if (result.next()) {
                generatedKey = result.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            this.release(preparedStatement);
        }
        return generatedKey;
    }

    /******************************************************* 返回结果集 ********************************************/
    /**
     * 执行一条SELECT语句 不关闭链接
     *
     * @param jdbcParameter
     * @return
     * @throws Exception
     */
    @Override
    public ResultSet executeQuery(JDBCParameter jdbcParameter) {
        Statement statement = null;
        Connection connection = null;
        ResultSet resultSet = null;
        try {
            if (jdbcParameter.getParameters() == null || jdbcParameter.getParameters().size() == 0) {
                connection = this.getConnection();
                statement = connection.createStatement(
                    ResultSet.TYPE_SCROLL_SENSITIVE,
                    ResultSet.CONCUR_READ_ONLY);
                logger.debug("SQL:" + jdbcParameter.getCommand());
                resultSet = statement.executeQuery(jdbcParameter.getCommand());
            } else {
                statement = this.getPreparedStatement(jdbcParameter);
                resultSet = ((PreparedStatement) statement).executeQuery();
            }
            if (resultSet == null) {
                this.release(statement);
                return null;
            } else {
                return resultSet;
            }
        } catch (Exception e) {
            logger.error("execute query error" + jdbcParameter.getCommand(), e);
            this.release(statement);
            return null;
        }
    }

    @Override
    public ResultSet executeQuery(String commandString) {
        return executeQuery(new JDBCParameter(commandString));
    }

    /**
     * 执行SELECT语句返回一行一列
     *
     * @param jdbcParameter
     * @return
     * @throws Exception
     */
    @Override
    public <P> P executeScalar(JDBCParameter jdbcParameter) {
        Object result = null;
        ResultSet rs = this.executeQuery(jdbcParameter);
        if (rs != null) {
            try {
                if (rs.next()) {
                    result = rs.getObject(1);
                }
            } catch (Exception e) {
                logger.error(jdbcParameter.getCommand(), e);
                result = null;
            } finally {
                this.release(rs);
            }
            return (P) result;
        } else {
            return null;
        }
    }

    @Override
    public <Z> Z executeScalar(String commandString) {
        return (Z) this.executeScalar(new JDBCParameter(commandString));
    }

    @Override
    public void release(Statement statement) {
        try {
            if (statement != null && statement.getConnection() != null) {
                if (statement.getConnection().getAutoCommit()) {
                    this.connectionHolder
                        .unbindConnection(statement.getConnection());
                    statement.close();
                }
            }
        } catch (SQLException e) {
            logger.error("release statement", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void release(ResultSet rs) {
        if (rs != null) {
            //如果是事务中的查询也不可关闭链接
            try {
                this.release(rs.getStatement());
                rs.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }
}