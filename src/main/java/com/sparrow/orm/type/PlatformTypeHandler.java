package com.sparrow.orm.type;

import com.sparrow.protocol.enums.Platform;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * @author by harry
 */
public class PlatformTypeHandler implements TypeHandler<Platform> {
    @Override public void setParameter(PreparedStatement ps, int i, Platform parameter) throws SQLException {
        ps.setInt(i, parameter.getPlatform());
    }

    @Override public Platform getResult(ResultSet rs, String columnName) throws SQLException {
        return Platform.getByPlatform(rs.getInt(columnName));
    }

    @Override public Platform getResult(ResultSet rs, int columnIndex) throws SQLException {
        return Platform.getByPlatform(rs.getInt(columnIndex));
    }

    @Override public Platform getResult(CallableStatement cs, int columnIndex) throws SQLException {
        return Platform.getByPlatform(cs.getInt(columnIndex));
    }


}
