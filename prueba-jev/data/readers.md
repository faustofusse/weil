# Lectores comparados

16 notificaciones reales (4 movimientos, 12 promociones, entre ellas el cupon
de $5.000 que el allowlist toma por compra) x 1 corrida por modelo. El prompt
sale de `app/worker/src/message.ts`, el mismo que corre en produccion.

| modelo | p50 | p90 | ok | mov | dir | monto | payee | cuenta | normalized sin digitos | USD/1k |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| gemini-3.5-flash-lite (directo) | **855 ms** | 1.097 ms | 100% | 100% | 100% | 100% | 100% | 94% | 75% | $0.073 |
| gemini-2.5-flash-lite (cf) | 1.351 ms | 1.536 ms | 100% | 100% | 100% | 100% | 100% | 100% | 75% | $0.074 |
| gemini-3.1-flash-lite (cf) | 1.543 ms | 1.916 ms | 100% | 100% | 94% | 100% | 100% | 100% | 75% | $0.072 |
| gemini-3.5-flash-lite (cf) | 1.483 ms | 1.658 ms | 69%* | 69% | 69% | 69% | 69% | 63% | 44% | $0.051 |
| glm-5.3-flash | 8.693 ms | 19.119 ms | 94% | 94% | 94% | 94% | 94% | 94% | **81%** | $0.335 |
| deepseek-v4-flash | 9.434 ms | 15.794 ms | 50% | 50% | 50% | 50% | 50% | 50% | 31% | $1.344 |
| glm-4.7-flash | 21.017 ms | 39.112 ms | 0% | 0% | 0% | 0% | 0% | 0% | 0% | $0.506 |
| kimi-k2.6 | 33.146 ms | 38.756 ms | 19% | 19% | 19% | 19% | 19% | 19% | 19% | $5.104 |

`ok` = devolvio un JSON parseable; las demas columnas son sobre el total de
corridas, asi que una respuesta ilegible cuenta como error en todas.
`USD/1k` = leer mil notificaciones, a los precios de los model pages
(2026-09-20). El prompt son ~370 tokens de entrada y la respuesta ~80.

(*) esa corrida se comio el limite de tasa de Unified Billing (`code 2018`),
no es culpa del modelo; el reintento con backoff se agrego despues.

## Que dicen los numeros

**Gemini Flash Lite gana y no esta cerca.** Diez veces mas rapido que el mejor
de los otros, cinco veces mas barato, y el unico que acierta las dieciseis. Las
tres versiones (2.5, 3.1, 3.5) empatan en contenido: la que corre hoy no esta
dejando nada arriba de la mesa, y bajar a la 2.5 no ahorraria nada.

**Cloudflare le suma ~500 ms** al mismo modelo (855 -> 1.351/1.483 ms). Pasar
por ahi se justifica por cache, logs y una sola factura, no por velocidad.

**Ninguno de los alojados en Workers AI sirve para esto.** No es tamano ni
precio: es que razonan. Kimi se toma 33 segundos y contesta una de cada cinco
veces; deepseek la mitad de las veces; glm-4.7 ninguna. El unico decente,
glm-5.3, acierta 15 de 16 pero con una mediana de 8,7 s y un p90 de 19 s, en
el paso que el usuario espera mirando la pantalla.

**El campo que a todos les cuesta es `normalized`**, la frase que se embebe
para buscar parecidos: un cuarto de las veces le meten el importe adentro.
Curiosamente el mejor ahi es glm-5.3 (81%), no Gemini (75%). Es el unico dato
de la tabla que invita a seguir mirando.

**Jev** (etapa 3, otra pregunta: un Choice sobre el arbol del usuario): p50
**307 ms** (714, 279, 277, 366, 307). No esta en la tabla porque no hace el
mismo trabajo, y TypeSafe no es proveedor de AI Gateway, asi que tampoco se
puede enrutar por ahi. Esta medido porque la pregunta que siempre aparece es
"no sera Jev lo lento", y es el 6% del total.
