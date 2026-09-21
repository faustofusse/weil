# Reporte de clasificación

Generado: 2026-09-19T03:10:15.350Z

| | n |
| --- | --- |
| mensajes en `lab.db` | 23804 |
| con símbolo de moneda (paso 1) | 148 |
| con dígitos pero sin moneda (descartados) | 10276 |
| puntuados por Jev | 148 |
| reconocidos por el allowlist de `Ingest.kt` | 54 |

## Histograma de `jev_score`

| rango | n | |
| --- | --- | --- |
| 0.0–0.1 | 69 | █████████████████████████████████████████████████████████████████████ |
| 0.1–0.2 | 4 | ████ |
| 0.2–0.3 | 0 |  |
| 0.3–0.4 | 0 |  |
| 0.4–0.5 | 0 |  |
| 0.5–0.6 | 0 |  |
| 0.6–0.7 | 1 | █ |
| 0.7–0.8 | 1 | █ |
| 0.8–0.9 | 19 | ███████████████████ |
| 0.9–1.0 | 54 | ██████████████████████████████████████████████████████ |

## Umbral vs. allowlist

`recuperados` = movimientos del allowlist que Jev también marca; `nuevos` = los que Jev marca y el allowlist no (lo interesante).

| umbral | marcados | recuperados / total allowlist | nuevos |
| --- | --- | --- | --- |
| 0.3 | 75 | 51 / 52 | 24 |
| 0.4 | 75 | 51 / 52 | 24 |
| 0.5 | 75 | 51 / 52 | 24 |
| 0.6 | 75 | 51 / 52 | 24 |
| 0.7 | 74 | 51 / 52 | 23 |
| 0.8 | 73 | 51 / 52 | 22 |
| 0.9 | 54 | 40 / 52 | 14 |

## Falsos negativos (allowlist sí, Jev < 0.5)

- `0.19` **Mercado Pago** — Recibiste $5.000 🍟 · 🍔$5.000 de regalo para pedir tu comida favorita

## Candidatos nuevos mejor puntuados (allowlist no los ve)

- `0.96` **Lemon Cash** — Recibiste una transferencia 💸 · Recibiste 30.000 ARS de Fausto Fusse
- `0.94` **ARQ (DolarApp)** — You received a deposit · 💰 You received 500,000 ARS
- `0.92` **ARQ (DolarApp)** — GlobalE Helly Hansen · 🛒 You paid 312.68 USD ($312.68 USDc)
- `0.92` **Telegram** — Entradas Boca Juniors Bot · ✅ Pago aprobado Acreditamos $18.000 para SAP-C01FC302 (San Pablo 08/09). ¡Gracias! Tus pagos en la app: https://app.entr
- `0.91` **Gmail** — Aviso Santander · Pagaste $20.000,00
- `0.91` **EBJ** — ✅ ¡Popular comprada! · Transferí $18.000 a entradasbocajuniors y subí el comprobante en la app. Cuenta: faustofusse@gmail.com
- `0.91` **Gmail** — Aviso Santander · Pagaste U$S100,00
- `0.90` **Gmail** — Aviso Santander · Pagaste U$S21,23
- `0.90` **Gmail** — Aviso Santander · Pagaste U$S21,23
- `0.90` **Gmail** — Aviso Santander · Pagaste $20.000,00
- `0.90` **Gmail** — Aviso Santander · Pagaste U$S21,23
- `0.90` **Gmail** — Aviso Santander · Pagaste U$S21,23
- `0.90` **Gmail** — Aviso Santander · Pagaste $2.500,00
- `0.90` **010001a00050ac6e-7c9c5b65-df40-42f3-aa85-c5164021210c-000000@smtpemail.invertironline.com** — Pago de Dividendos · No responder este email ya que sólo es de carácter informativo. Argentina, Viernes 14 de Agosto de 2026 Aviso por pago d
- `0.89` **Gmail** — Aviso Santander · Pagaste $2.500,00
- `0.89` **010001a000d59ff9-edf92292-9e76-473d-893d-167294b81c3d-000000@smtpemail.invertironline.com** — Estado de la Transacción N°185183784 · 14 de Agosto de 2026 Compra Terminada Fausto Fusse Cliente: 1876614 Transacción: 185183784 Estado: Terminada Símbolo: S1
- `0.89` **010001a000d50b16-a8a1c369-834c-4757-8993-6383852abc8d-000000@smtpemail.invertironline.com** — Estado de la Transacción N°185183992 · 14 de Agosto de 2026 Compra Terminada Fausto Fusse Cliente: 1876614 Transacción: 185183992 Estado: Terminada Símbolo: ME
- `0.88` **bounces+21666613-f539-wallet=fausto.ar@em81.arqfinance.com** — You sent 25,973.42 ARS to Fausto Fusse · You sent 25,973.42 ARS to Fausto Fusse 96 <style type="t
- `0.88` **bounces+21666613-f539-wallet=fausto.ar@em81.arqfinance.com** — You sent 33,037.96 ARS to Fausto Fusse · You sent 33,037.96 ARS to Fausto Fusse 96 <style type="t
- `0.86` **bounces+21666613-f539-wallet=fausto.ar@em81.arqfinance.com** — You sent 200 USD to Fausto Fusse · You sent 200 USD to Fausto Fusse 96

## Muestra de lo descartado en el paso 1 (dígitos, sin moneda)

Si acá aparecen movimientos, el prefiltro de moneda es demasiado estrecho.

- **Google** — Watch match recap · D.C. United 1 - 2 Inter Miami · Sat, Mar 7
- **WhatsApp** — Squanzion (2 messages): Kozba · Por si quieren pasarse chee
- **WhatsApp** — Squanzion (32 messages): Kozba · Jajajajaj
- **WhatsApp** — Agostina · 2 new messages
- **Maps** — Updating offline maps · Map 1 • 81%
- **Spotify** — Ogre Battle - Remastered 2011 · Queen
- **WhatsApp** — Pantallas DEX (3 messages): ~ Camila · Le dije que lo prueben cualquier cosa, igual yo lo probé y todo bien
- **WhatsApp** — Squanzion · 12 new messages
- **WhatsApp** — beautiful family💗 · 3 new messages
- **System UI** — 15% battery left · Turn on Extreme Battery Saver to extend battery life
- **WhatsApp** — WhatsApp · 69 messages from 5 chats
- **WhatsApp** — GM - DEX 📲🚘 (2 messages): Lucio · estamos tratando de salir
- **WhatsApp** — Null - BD - TP (2 messages): ~ ShiMo · Entrega DER En esta primera entrega deberá enviarse solamente el DER del sistema en un archivo formato imagen, preferent
- **Google** — 17° in Buenos Aires · Cloudy · See full forecast
- **Maps** — 280 m · Turn left onto Av. Córdoba · No text
- **Maps** — Drive 9 min (3.4 km) · Arrive 11:34 PM · Home
- **WhatsApp** — GM - DEX 📲🚘 (2 messages): ~ Facundo Torres GM · El banco no nos da lo que es cuota n°1 y gastos de otorgamiento (quebranto), y quiero ver de que manera lo puedo calcula
- **WhatsApp** — WhatsApp · 27 messages from 2 chats
- **Spotify** — Trátame Suavemente - Remasterizado 2007 · Soda Stereo
- **Spotify** — In the Air Tonight - 2015 Remaster · Phil Collins