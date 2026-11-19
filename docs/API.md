# API — Larp Launcher Backend

Contrato esperado pelo app ao falar com o backend Cloudflare Workers + KV + Queues.

Base URL configurável via `app/src/main/assets/servidor.txt` ou `BuildConfig.API_BASE_URL`.

Todos os endpoints (exceto `/auth/login` e `/auth/discord`) exigem header `Authorization: Bearer <token>`. Respostas de erro: `{"error": "<code>", "message": "<texto>"}`.

## Tabela de endpoints

| Método | Path | Auth | Descrição |
|--------|------|------|-----------|
| GET | `/auth/login?challenge=<s>` | — | Inicia login PKCE, retorna challenge |
| GET | `/auth/discord?state=<s>` | — | Retorna `{url, state}` para redirect ao Discord |
| POST | `/auth/token` | — | Troca `{grant, verifier}` por `{token, expiresAt, user}` |
| POST | `/auth/refresh` | bearer | Renova token |
| GET | `/auth/me` | bearer | Retorna o usuário corrente |
| GET | `/mods?cursor=&limit=` | bearer | Lista mods oficiais |
| GET | `/mods/{id}` | bearer | Detalhe de um mod |
| POST | `/mods/{id}/ticket` | bearer | Gera URL de download assinada + chave AES |
| POST | `/mods/install` | bearer | Marca mods como instalados (server-side) |
| GET | `/rooms` | bearer | Lista salas ativas |
| POST | `/rooms` | bearer | Cria sala |
| GET | `/rooms/{id}` | bearer | Detalhe de uma sala |
| POST | `/rooms/{id}/join` | bearer | Entra na sala; retorna `{roomId, voiceToken, iceServers}` |
| POST | `/rooms/{id}/leave` | bearer | Sai da sala |
| DELETE | `/rooms/{id}` | bearer (admin) | Fecha a sala |
| POST | `/rooms/{id}/voice/offer` | bearer | WebRTC offer (SDP) |
| POST | `/rooms/{id}/voice/answer` | bearer | WebRTC answer (SDP) |
| POST | `/rooms/{id}/voice/ice` | bearer | ICE candidate |
| GET | `/rooms/{id}/voice/ice-servers` | bearer | Lista de ICE servers (Cloudflare Calls) |
| GET | `/announcements` | bearer | Lista avisos do mural |
| POST | `/announcements` | bearer (admin) | Publica aviso |
| DELETE | `/announcements/{id}` | bearer (admin) | Remove aviso |
| GET | `/broadcast/recent?limit=` | bearer | Broadcasts recentes |
| POST | `/broadcast` | bearer (admin) | Envia broadcast (Queue → fanout para todos os WebSocket clients) |
| WS | `/realtime?token=<t>` | bearer | Eventos em tempo real (rooms, broadcast, announcements, voice, presence) |

## Formato do pacote RGM1

Mods oficiais são entregues como pacotes `.rgm`:

```
[20 bytes header]              "RGM1" + 16 bytes IV
[N bytes ciphertext]           AES-CBC(PKCS5) do ZIP
[32 bytes HMAC-SHA256]         tag de autenticação
```

A chave AES é derivada por HMAC-SHA256:

- `enc_key = HMAC-SHA256(masterKey, "enc")`
- `mac_key = HMAC-SHA256(masterKey, "mac")`

`masterKey` é retornada em `POST /mods/{id}/ticket` no campo `key` (base64).

## Tipos

### User

```json
{
  "id": "u_abc",
  "name": "Alice",
  "avatar": "https://cdn.discordapp.com/avatars/...",
  "discordId": "1234567890",
  "owner": false,
  "admin": true,
  "moderator": false,
  "guilds": [
    {"id": "g_1", "name": "Server do Larp", "icon": "...", "owner": false}
  ]
}
```

### Mod

```json
{
  "id": "mod_foo",
  "name": "Foo Mod",
  "description": "Adiciona foo ao jogo",
  "author": "larp-team",
  "version": "1.2.3",
  "size": 102400,
  "checksum": "sha256...",
  "coverUrl": "https://cdn.../foo.png",
  "tags": ["gameplay", "ui"],
  "isOfficial": true,
  "updatedAt": 1730000000000
}
```

### ModTicket

```json
{
  "id": "mod_foo",
  "name": "Foo Mod",
  "downloadUrl": "https://cdn.../foo.rgm?sig=...",
  "key": "base64(masterKey)",
  "checksum": "sha256...",
  "size": 102400
}
```

### Room

```json
{
  "id": "r_xyz",
  "name": "Sala da turma",
  "game": "com.innersloth.spacemafia",
  "ownerId": "u_abc",
  "ownerName": "Alice",
  "mods": ["mod_foo"],
  "maxPlayers": 16,
  "playerCount": 4,
  "locked": false,
  "createdAt": 1730000000000,
  "voiceEnabled": true
}
```

### CreateRoomRequest

```json
{
  "name": "Sala da turma",
  "game": "com.innersloth.spacemafia",
  "mods": ["mod_foo"],
  "maxPlayers": 16,
  "locked": false,
  "voiceEnabled": true
}
```

### JoinRoomResponse

```json
{
  "roomId": "r_xyz",
  "voiceToken": "abc123",
  "iceServers": [
    {"urls": ["stun:stun.larp.workers.dev:3478"]},
    {"urls": ["turn:turn.larp.workers.dev:3478"], "username": "user", "credential": "pass"}
  ]
}
```

### Announcement

```json
{
  "id": "a_1",
  "title": "Manutenção",
  "body": "Servidor fica off das 03h às 04h",
  "authorName": "Alice",
  "pinned": true,
  "color": "#FFB3261E",
  "createdAt": 1730000000000,
  "updatedAt": 1730000000000,
  "expiresAt": 1730100000000
}
```

### Broadcast

```json
{
  "id": "b_1",
  "message": "Servidor reiniciando em 5 minutos",
  "authorName": "Alice",
  "severity": "warn",
  "createdAt": 1730000000000
}
```

`severity`: `info` | `warn` | `critical`.

### VoiceOffer / VoiceAnswer

```json
{
  "sdp": "v=0\r\n...",
  "type": "offer",
  "roomId": "r_xyz"
}
```

### VoiceIceCandidate

```json
{
  "candidate": "candidate:...",
  "sdpMid": "audio",
  "sdpMLineIndex": 0,
  "roomId": "r_xyz",
  "userId": "u_def"
}
```

## WebSocket — eventos `/realtime`

Mensagens trocadas em JSON com campo `type`:

```json
{ "type": "broadcast", "payload": { ...BroadcastDto } }
{ "type": "announcement", "payload": { ...AnnouncementDto } }
{ "type": "room", "payload": { ...RoomEventDto } }
{ "type": "voice_offer", "payload": { "sdp": "...", "type": "offer", "roomId": "r_xyz", "userId": "u_def" } }
{ "type": "voice_answer", "payload": { "sdp": "...", "type": "answer", "roomId": "r_xyz", "userId": "u_def" } }
{ "type": "voice_ice", "payload": { "candidate": "...", "sdpMid": "audio", "sdpMLineIndex": 0, "roomId": "r_xyz", "userId": "u_def" } }
{ "type": "presence", "payload": { "userId": "u_def", "online": true } }
```

`RoomEventDto`:

```json
{
  "type": "join|leave|mods_updated|kicked",
  "roomId": "r_xyz",
  "userId": "u_def",
  "user": { ...UserDto },
  "participant": { ...RoomParticipantDto },
  "mods": ["mod_foo"],
  "timestamp": 1730000000000
}
```

`RoomParticipantDto`:

```json
{
  "userId": "u_def",
  "name": "Bob",
  "avatar": "...",
  "muted": true,
  "speaking": false,
  "isAdmin": false
}
```

## Fluxo de Discord OAuth (PKCE)

```
App           Larp API         Discord
 │             │                 │
 │── GET /auth/login?challenge=X ─→
 │←─ 200 { challenge: X } ──────
 │── GET /auth/discord?state=X ──→
 │←─ 200 { url: <discord-url> } ─
 │── (abre Custom Tab) ──────────────→ /authorize?...
 │                                  ←─ redirect?code=...&state=X
 │              │←── /auth/discord/callback?code=...&state=X
 │              │── troca code por access_token Discord
 │              │── cria usuário no Larp
 │              │── redirect com grant: com.larp.launcher://oauth?grant=<token-curto>
 │←─────────────── (deep link)
 │── POST /auth/token { grant, verifier } ──→
 │←─ 200 { token, expiresAt, user } ─────────
```

## Cloudflare Calls (voz)

O backend usa **Cloudflare Calls** para TURN/STUN. O endpoint `/rooms/{id}/voice/ice-servers` retorna a lista de ICE servers. O cliente (app) cria `PeerConnection` via WebRTC e troca SDP via `/rooms/{id}/voice/{offer|answer}` e ICE via `/rooms/{id}/voice/ice`. Em paralelo, o WebSocket `/realtime` entrega os SDP/ICE do outro lado.

## Sugerido: estrutura do Worker

```
worker/
├── src/
│   ├── index.ts          (rotas HTTP)
│   ├── auth.ts           (PKCE + Discord OAuth)
│   ├── mods.ts           (mods + tickets)
│   ├── rooms.ts          (salas)
│   ├── announcements.ts  (mural)
│   ├── broadcast.ts      (broadcast + Queue)
│   ├── realtime.ts       (WebSocket /realtime)
│   └── voice.ts          (WebRTC signaling + Cloudflare Calls)
├── wrangler.toml
├── package.json
└── tsconfig.json
```

Bindings sugeridos:

- `KV = larp_kv` (sessões, presence, rate-limit)
- `QUEUE = larp_broadcast` (fanout de broadcast)
- `DURABLE_OBJECT = ROOM` (estado de sala + voice signaling) — opcional se quiser Durable Objects
- `CALLS = larp_calls` (Cloudflare Calls API)
- `D1 = larp_db` (tabelas: users, mods, announcements, rooms, room_participants)
