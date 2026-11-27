# Wire fixtures

JSON bodies exchanged between the client SDKs and the Grayskull server. Every SDK should
serialize its requests to exactly these bodies and parse these responses, so the Java, Go and
Python clients cannot drift apart on the wire.

| File | Endpoint | Direction |
| --- | --- | --- |
| `get_secret_data_response_v1.json` | `GET /v1/projects/{projectId}/secrets/{secretName}/data` | server to client |
| `batch_get_secrets_request_v1.json` | `POST /v1/secrets/batch` | client to server |
| `batch_get_secrets_response_v1.json` | `POST /v1/secrets/batch` | server to client |
| `../heartbeat/client_heartbeat_v1.json` | `POST /v1/client/heartbeat` | client to server |

Response fixtures list only the fields the SDKs read. The server may send more fields
(`creationTime`, `createdBy`, `state` and so on), and clients must ignore them. The Python
integration suite (`clients/python/it`) checks that the real server still sends every field
listed here, with the same JSON type.

Add a new `_v2` file instead of editing one in place when the wire format changes.
