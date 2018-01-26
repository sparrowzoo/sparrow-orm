package com.sparrow.orm.query.sql.impl.operation;

import com.sparrow.constant.magic.SYMBOL;
import com.sparrow.orm.EntityManager;
import com.sparrow.orm.Field;
import com.sparrow.orm.Parameter;
import com.sparrow.orm.query.Criteria;
import com.sparrow.orm.query.CriteriaField;
import com.sparrow.orm.query.sql.RelationOperationEntity;
import com.sparrow.orm.query.sql.RelationalOperation;

/**
 * Created by harry on 2018/1/15.
 */
public class ModOperation implements RelationalOperation {
    @Override
    public RelationOperationEntity operation(Criteria criteria) {
        CriteriaField criteriaField = criteria.getField();
        EntityManager entityManager = EntityManager.get(criteriaField.getAlias());
        Field field = entityManager.getField(criteriaField.getName());
        String condition = (criteria.isAlias() ? criteria.getField().getAlias() + SYMBOL.DOT : SYMBOL.EMPTY) + field.getColumnName() + SYMBOL.BLANK + criteria.getCriteriaEntry().getKey().rendered() + SYMBOL.BLANK + criteria.getCriteriaEntry().getMod() + SYMBOL.BLANK + "=?";
        Parameter parameter = new Parameter(field, criteria.getCriteriaEntry().getValue());
        return new RelationOperationEntity(condition, parameter);
    }
}