package com.nukleus.vrmeeting.accountdeletion;

import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.model.relational.Sequence;
import org.hibernate.mapping.Table;
import org.hibernate.tool.schema.spi.SchemaFilter;
import org.hibernate.tool.schema.spi.SchemaFilterProvider;

// Existing ddl-auto=update must NOT silently replace the reviewed PostgreSQL migration.
public final class AccountDeletionSchemaFilter implements SchemaFilterProvider {
    private static final SchemaFilter FILTER = new SchemaFilter() {
        public boolean includeNamespace(Namespace namespace) { return true; }
        public boolean includeTable(Table table) {
            return !"account_deletion_requests".equalsIgnoreCase(table.getName());
        }
        public boolean includeSequence(Sequence sequence) { return true; }
    };
    public SchemaFilter getCreateFilter() { return FILTER; }
    public SchemaFilter getDropFilter() { return FILTER; }
    public SchemaFilter getTruncatorFilter() { return FILTER; }
    public SchemaFilter getMigrateFilter() { return FILTER; }
    // Explicit schema validation, if selected by operators, should include the new table.
    public SchemaFilter getValidateFilter() { return SchemaFilter.ALL; }
}
