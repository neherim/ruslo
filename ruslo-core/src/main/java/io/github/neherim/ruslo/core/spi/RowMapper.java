package io.github.neherim.ruslo.core.spi;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Maps the current row of a result set to an object. */
@FunctionalInterface
public interface RowMapper<T> {
    T mapRow(ResultSet rs) throws SQLException;
}
