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

package com.sparrow.orm.query.sql.impl.operation;

import com.sparrow.constant.magic.SYMBOL;
import com.sparrow.container.ClassFactoryBean;
import com.sparrow.orm.*;
import com.sparrow.orm.query.Criteria;
import com.sparrow.orm.query.CriteriaField;
import com.sparrow.orm.query.sql.RelationOperationEntity;
import com.sparrow.orm.query.sql.RelationalOperation;

/**
 * @author by harry
 */
public class BinaryOperation implements RelationalOperation {
    private ClassFactoryBean<EntityManager> entityManagerFactoryBean=EntityManagerFactoryBean.getInstance();

    @Override
    public RelationOperationEntity operation(Criteria criteria) {
        CriteriaField criteriaField = criteria.getField();
        EntityManager entityManager = entityManagerFactoryBean.getObject(criteriaField.getAlias());
        Field field = entityManager.getField(criteriaField.getName());
        if(field==null){
            throw new IllegalArgumentException(criteriaField.getAlias()+SYMBOL.DOT+criteriaField.getName()+" not found");
        }
        String condition = (criteria.isAlias() ? criteriaField.getAlias() + SYMBOL.DOT : SYMBOL.EMPTY) + field.getColumnName() + SYMBOL.BLANK + criteria.getCriteriaEntry().getKey().rendered() + " ? ";
        Parameter parameter = new Parameter(field, criteria.getCriteriaEntry().getValue());
        return new RelationOperationEntity(condition, parameter);
    }
}
