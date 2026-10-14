import os

file_path = '/Users/ctqwa/Dev/Android/JugglucoNG/Common/src/main/res/values-fr/strings.xml'

keys_to_check = [
    'alert_sound', 
    'clear_app_data_title', 
    'clear_history_title',
    'export_history_title',
    'factory_reset_outcome',
    'import_history'
]

with open(file_path, 'rb') as f:
    content = f.read()
    
    for key in keys_to_check:
        key_bytes = key.encode('utf-8')
        pos = content.find(key_bytes)
        if pos != -1:
            print(f"--- Key: {key} ---")
            start = max(0, pos - 50)
            end = min(len(content), pos + 100)
            snippet = content[start:end]
            print(f"Snippet: {snippet}")
            # Try to decode
            try:
                print(f"Decoded: {snippet.decode('utf-8')}")
            except Exception as e:
                print(f"Decode error: {e}")
        else:
            print(f"Key {key} not found in {file_path}")
