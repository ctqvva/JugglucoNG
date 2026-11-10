import os
import re

root_dirs = [
    '/Users/ctqwa/Dev/Android/JugglucoNG/Common/src/mobile/res',
    '/Users/ctqwa/Dev/Android/JugglucoNG/Common/src/main/res'
]

# Regex to find string tags without formatted attribute
# Matches: <string name="something">
tag_pattern = re.compile(r'<string\s+name="([^"]+)"\s*>')

def process_file(file_path):
    encodings = ['utf-8', 'latin-1', 'cp1252']
    content = None
    used_encoding = None
    
    for enc in encodings:
        try:
            with open(file_path, 'r', encoding=enc) as f:
                content = f.read()
            used_encoding = enc
            break
        except UnicodeDecodeError:
            continue
            
    if content is None:
        print(f"Failed to read {file_path} with known encodings")
        return

    original_content = content
    
    # 1. Fix formatted="false" in html.xml
    if file_path.endswith('html.xml'):
        def replacement(match):
            name = match.group(1)
            return f'<string name="{name}" formatted="false">'
        
        content = tag_pattern.sub(replacement, content)
        
        # Also fix {str} in html.xml if present (French file had it)
        content = content.replace('{str}', '%1$s') 

    # 2. Fix {str} in French strings.xml
    if file_path.endswith('strings.xml') and 'values-fr' in file_path:
        # Replace {str} with %1$s (assuming it's a string placeholder)
        if '{str}' in content:
            print(f"Found {{str}} in {file_path}")
            content = content.replace('{str}', '%1$s')

    if content != original_content:
        print(f"Updating {file_path} (encoding: {used_encoding})")
        with open(file_path, 'w', encoding=used_encoding) as f:
            f.write(content)

for root_dir in root_dirs:
    for root, dirs, files in os.walk(root_dir):
        for file in files:
            if file == 'html.xml' or (file == 'strings.xml' and 'values-fr' in root):
                process_file(os.path.join(root, file))
