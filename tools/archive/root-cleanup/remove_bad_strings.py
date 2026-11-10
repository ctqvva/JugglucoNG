import re

file_path = '/Users/ctqwa/Dev/Android/JugglucoNG/Common/src/main/res/values-fr/strings.xml'

keys_to_remove = [
    'alert_sound',
    'batch_code_supporting',
    'clear_app_data_title',
    'clear_history_title',
    'enter_sensor_label_desc',
    'enter_transmitter_desc',
    'export_history_title',
    'factory_reset_outcome',
    'import_history',
    'import_history_title',
    'invalid_device_name',
    'no_qr_found',
    'select_export_format',
    'sensor_not_init',
    'transmitter_id_supporting'
]

# Read the file
with open(file_path, 'r', encoding='utf-8') as f:
    lines = f.readlines()

new_lines = []
removed_count = 0

for line in lines:
    should_remove = False
    for key in keys_to_remove:
        if f'name="{key}"' in line:
            should_remove = True
            removed_count += 1
            print(f"Removing: {line.strip()}")
            break
    
    if not should_remove:
        new_lines.append(line)

print(f"Removed {removed_count} lines.")

with open(file_path, 'w', encoding='utf-8') as f:
    f.writelines(new_lines)
