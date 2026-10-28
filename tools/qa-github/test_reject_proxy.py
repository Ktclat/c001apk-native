"""New source-only checks; run only after approval. No listener is started by these tests."""
import ast
from pathlib import Path
import unittest
from reject_proxy import RESPONSE, classify

class RejectorContract(unittest.TestCase):
    def test_fixed_response_length(self):
        head,body=RESPONSE.split(b'\r\n\r\n')
        self.assertIn(b'HTTP/1.1 502 Bad Gateway',head)
        self.assertIn(b'Content-Length: '+str(len(body)).encode(),head)
    def test_closed_authority_counters(self):
        self.assertEqual('default_https',classify(b'CONNECT 198.51.100.17:443 HTTP/1.1'))
        self.assertEqual('production_media',classify(b'CONNECT 198.51.100.18:443 HTTP/1.0'))
        self.assertEqual('other_connect',classify(b'CONNECT hidden.example:443 HTTP/1.1'))
        self.assertEqual('other_request',classify(b'GET /private?not-recorded HTTP/1.1'))
    def test_no_outgoing_or_resolver_api(self):
        tree=ast.parse(Path(__file__).with_name('reject_proxy.py').read_text())
        calls={n.func.attr for n in ast.walk(tree) if isinstance(n,ast.Call) and isinstance(n.func,ast.Attribute)}
        self.assertFalse(calls & {'connect','connect_ex','create_connection','getaddrinfo','gethostbyname','wrap_socket'})
