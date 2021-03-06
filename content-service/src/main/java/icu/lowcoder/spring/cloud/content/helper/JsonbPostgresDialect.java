package icu.lowcoder.spring.cloud.content.helper;

import org.hibernate.dialect.PostgreSQL94Dialect;

import java.sql.Types;

/**
 * @author  yanhan
 * description: jsonb 方言
 * date:  create in 2021/3/1 11:03 上午
 */
public class JsonbPostgresDialect extends PostgreSQL94Dialect {

    public JsonbPostgresDialect() {
        this.registerColumnType(Types.JAVA_OBJECT,"jsonb");
    }

}
