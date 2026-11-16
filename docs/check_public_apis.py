"""Read-only public/guest API contract checks. Never log tokens or artwork metadata."""
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

AGENT = 'UnifiedArt/0.4 (Android; https://github.com/shadowsight00/UnifiedArt)'
report = {}

def request(url, data=None):
    headers = {'User-Agent': AGENT}
    body = urllib.parse.urlencode(data).encode() if data is not None else None
    with urllib.request.urlopen(urllib.request.Request(url, data=body, headers=headers), timeout=15) as response:
        return json.loads(response.read())

def failure(error):
    if isinstance(error, urllib.error.HTTPError):
        return f'HTTP {error.code} from this runner'
    return f'Unavailable from this runner ({type(error).__name__})'

try:
    result = request('https://e621.net/posts.json?limit=1&tags=landscape')
    assert isinstance(result.get('posts'), list), 'Unexpected posts response'
    report['e621'] = {'status': 'ok', 'returned': len(result['posts'])}
except Exception as error:
    report['e621'] = {'status': 'unavailable', 'detail': failure(error)}

sid = None
try:
    result = request('https://inkbunny.net/api_login.php', {'username': 'guest'})
    sid = result.get('sid')
    assert sid and 'error_code' not in result, 'Guest session unavailable'
    time.sleep(1.2)
    result = request('https://inkbunny.net/api_userrating.php', {'sid': sid, **{f'tag[{i}]': 'yes' for i in range(2, 6)}})
    assert 'error_code' not in result, 'Guest rating selection unavailable'
    time.sleep(1.2)
    result = request('https://inkbunny.net/api_search.php', {'sid': sid, 'text': 'landscape', 'submissions_per_page': '1', 'page': '1', 'orderby': 'create_datetime'})
    assert 'error_code' not in result and isinstance(result.get('submissions'), list), 'Unexpected search response'
    report['Inkbunny'] = {'status': 'ok', 'returned': len(result['submissions'])}
except Exception as error:
    report['Inkbunny'] = {'status': 'unavailable', 'detail': failure(error)}
finally:
    if sid:
        try:
            time.sleep(1.2)
            request('https://inkbunny.net/api_logout.php', {'sid': sid})
        except Exception:
            pass
    sid = None

path = Path('app/build/reports/api-connectivity.json')
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text(json.dumps(report, indent=2))
for name, result in report.items():
    print(f"Public API check: {name}: {result['status']}" + (f" — {result['detail']}" if 'detail' in result else ''))
