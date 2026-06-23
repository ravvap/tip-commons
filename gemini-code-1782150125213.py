import pandas as pd

# Load template data (skipping the first row description text)
df = pd.read_excel('Retention_Table_Registry_Template.xlsx', sheet_name='Registry Template', skiprows=1)

# Filter out empty entries if the user didn't fill all 75 placeholder slots
df = df.dropna(subset=['table_name', 'sub_category_code'])

print("-- Automated Generated Inserts from Excel Manifest --")
for index, row in df.iterrows():
    # Format boolean flags explicitly for PostgreSQL string compatibility
    is_active = str(row['is_active']).upper()
    legal_hold = str(row['legal_hold_status']).upper()
    
    # Generate parameterized insert script
    sql_script = f"""
    INSERT INTO txn.retention_table_registry (
        id, schema_name, table_name, classification_pattern, is_active, 
        basis_date_column, rtn_effective_date, rtn_end_date, sub_category_id, 
        legal_hold_status, created_by
    ) VALUES (
        gen_random_uuid(),
        '{row['schema_name']}',
        '{row['table_name']}',
        '{row['classification_pattern']}',
        {is_active},
        '{row['basis_date_column']}',
        '{row['rtn_effective_date']}',
        '{row['rtn_end_date']}',
        (SELECT id FROM txn.retention_sub_categories WHERE code = '{row['sub_category_code']}' LIMIT 1),
        {legal_hold},
        '{row['created_by']}'
    );"""
    print(sql_script)