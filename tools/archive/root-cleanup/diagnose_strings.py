import re

file_path = '/Users/ctqwa/Dev/Android/JugglucoNG/Common/src/main/res/values-fr/strings.xml'

try:
    with open(file_path, 'r', encoding='utf-8') as f:
        lines = f.readlines()
        
    for i, line in enumerate(lines):
        # check for \u
        if '\\u' in line:
            print(f"Line {i+1} contains \\u: {line.strip()}")
            # Check if valid
            # Find all \uXXXX
            # ...
            
        # check for {str}
        if '{str}' in line:
            print(f"Line {i+1} contains {{str}}: {line.strip()}")
            
        # check for alone \ not used for escaping ' or " or n or @ or u
        # naive check
        
        # Check specific resource names mentioned in error
        if 'name="alert_sound"' in line:
             print(f"Line {i+1} definition: {line.strip()}")
        if 'name="factory_reset_outcome"' in line:
             print(f"Line {i+1} definition: {line.strip()}")

except Exception as e:
    print(f"Error reading file: {e}")

# Also check html.xml for batch_code_supporting
html_path = '/Users/ctqwa/Dev/Android/JugglucoNG/Common/src/mobile/res/values-fr/html.xml'
try:
    with open(html_path, 'r', encoding='utf-8') as f:
        lines = f.readlines()
    found = False
    for i, line in enumerate(lines):
        if 'batch_code_supporting' in line:
            print(f"Found batch_code_supporting in html.xml at line {i+1}: {line.strip()}")
            found = True
    if not found:
        print("batch_code_supporting NOT found in html.xml")
except Exception as e:
    print(f"Error reading html.xml: {e}")
