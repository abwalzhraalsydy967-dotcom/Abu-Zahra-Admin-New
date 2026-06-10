import re

with open("/opt/abuzahra/server.py", "r") as f:
    content = f.read()

# Use regex to find and patch api_stream_start
pattern = r'(async def api_stream_start\(request\):.*?if device_id:\s+)cmd = queue_command'
def replacer(m):
    prefix = m.group(1)
    return prefix + 'data["server_url"] = data.get("server_url") or "https://alsydyabwalzhra.online"\n            cmd = queue_command'

new_content, n = re.subn(pattern, replacer, content, count=1, flags=re.DOTALL)
if n > 0:
    with open("/opt/abuzahra/server.py", "w") as f:
        f.write(new_content)
    print("PATCHED: api_stream_start now injects server_url")
else:
    print("ERROR: Could not find pattern to patch")
    # Debug: show what's around api_stream_start
    idx = content.find("async def api_stream_start")
    if idx >= 0:
        print("Found at index", idx)
        print(repr(content[idx:idx+500]))