# CMP WebRTC Call

**Дата:** 2026-09-20  
**Статус:** клиент Call/Review живой; медиа клиент ↔ OpenAI Realtime. Ключ `OPENAI_REALTIME_API_KEY` на сервере (CI/CD), не в приложении.  
**Связанные документы:** [`../integrations/2026-09-18-mobile-api.md`](../integrations/2026-09-18-mobile-api.md), [`2026-09-17-cmp-client.md`](2026-09-17-cmp-client.md)

## Решение

Один `expect fun createRealtimeCall()`. Android+iOS: `com.shepeliev:webrtc-kmp:0.125.11` (общий код в `src/webrtcMain`, это `srcDir` android/ios — JVM у библиотеки нет). Desktop: `dev.onvoid.webrtc:webrtc-java:0.14.0` + native classifier хоста.

Порядок как в HTTP-контракте: mic → SDP offer → один `POST /v1/sessions/{id}/rtc` (`application/sdp`). Trickle ICE нет. Субтитры и `turns` — data channel `oai-events`, не Ktor. Hangup → `POST .../complete` → Review поллит `GET .../review`. Карточка «Последний разговор» остаётся моком (`ReviewRoute()` без sessionId).

iOS: линковать `WebRTC` из [webrtc-sdk/Specs](https://github.com/webrtc-sdk/Specs) `125.6422.07` (SPM в `iosApp.xcodeproj`). Android: `RECORD_AUDIO` + запрос в `MainActivity`. Desktop: Pulse/ALSA через native webrtc-java.

Клиент `ek_` не видит. coturn в этот срез не входит.
