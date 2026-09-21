# Lectores comparados

16 notificaciones reales (4 movimientos, 12 promociones, entre ellas el cupon
de $5.000 que el allowlist toma por compra) x 1 corrida por modelo. El prompt
sale de `app/worker/src/message.ts`, el mismo que corre en produccion.

## Con el razonamiento apagado (`--no-think`)

| modelo | p50 | p90 | ok | aciertos | normalized sin digitos | USD/1k | pensamiento |
| --- | --- | --- | --- | --- | --- | --- | --- |
| gemini-3.5-flash-lite (directo) | **855 ms** | 1.097 ms | 100% | 16/16 | 75% | **$0.073** | - |
| gemini-2.5-flash-lite (cf) | 1.351 ms | 1.536 ms | 100% | 16/16 | 75% | $0.074 | - |
| gemini-3.1-flash-lite (cf) | 1.543 ms | 1.916 ms | 100% | 15/16 | 75% | $0.072 | - |
| deepseek-v4-flash | 2.080 ms | 2.412 ms | 100% | 16/16 | 38% | $0.288 | 0 |
| glm-5.3-flash | 2.512 ms | 3.158 ms | 100% | 16/16 | 75% | $0.105 | 43 |
| kimi-k2.6 | 3.547 ms | 4.978 ms | 100% | 16/16 | **0%** | $0.859 | 0 |
| glm-4.7-flash | 23.300 ms | 42.088 ms | 25% | 4/16 | 25% | $0.497 | 4.538 |

`pensamiento` = caracteres de `reasoning_content` por respuesta, o sea cuanto
pensó en voz alta. No hay interruptor portable: cada familia lo escribe
distinto y el endpoint unificado reenvia lo que no reconoce, asi que van las
tres formas juntas (`thinking`, `chat_template_kwargs`, `reasoning_effort`) y
la tabla dice cual agarro. glm-4.7 es el unico que las ignora las tres.

## Con el razonamiento prendido (como venian de fabrica)

| modelo | p50 | p90 | ok | aciertos | pensamiento |
| --- | --- | --- | --- | --- | --- |
| glm-5.3-flash | 8.693 ms | 19.119 ms | 94% | 15/16 | ~1.500 |
| deepseek-v4-flash | 9.434 ms | 15.794 ms | 50% | 8/16 | ~1.900 |
| glm-4.7-flash | 21.017 ms | 39.112 ms | 0% | 0/16 | 4.500 |
| kimi-k2.6 | 33.146 ms | 38.756 ms | 19% | 3/16 | ~3.000 |

`ok` = devolvio un JSON parseable; una respuesta ilegible cuenta como error en
todas las columnas. `USD/1k` = leer mil notificaciones a los precios de los
model pages (2026-09-20); el prompt son ~370 tokens y la respuesta ~80.

## Que dicen los numeros

**Apagar el razonamiento los arregla.** No era que no supieran leer un aviso:
era que pensaban en voz alta hasta quedarse sin tokens antes de escribir el
JSON. glm-5.3 pasa de 8,7 s a 2,5 y de 15/16 a 16/16; deepseek de 9,4 s a 2,1
y de la mitad a todas; kimi de 33 s a 3,5 y de 3/16 a 16/16. El costo cae con
el tiempo, porque lo que se pagaba eran tokens de pensamiento: kimi baja de
$5,10 a $0,86 cada mil lecturas.

**Gemini Flash Lite sigue ganando igual**: 855 ms contra los 2.080 del mejor
de los otros, y $0,073 contra $0,105. Sin razonar, tres de los cuatro
alojados en Workers AI aciertan las dieciseis, asi que la eleccion ya no es
"el unico que funciona" sino "el mas rapido y el mas barato", que es una
eleccion mas comoda de revisar mas adelante.

**glm-4.7 no tiene arreglo**: ignora las tres banderas, sigue en 23 s y 4.538
caracteres de pensamiento por respuesta, y contesta una de cada cuatro veces.

**`normalized` sigue siendo el campo debil y ahora se ve mejor**: kimi lo
escribe con digitos **siempre**, deepseek 62% de las veces, Gemini y glm-5.3
un cuarto. Que todos fallen y que el mejor solo llegue a 75% dice que el
problema es la instruccion, no el modelo: esa frase probablemente tenga que
armarse en codigo desde payee + cuenta, que es literalmente lo que pide.

**Jev** (etapa 3, otra pregunta: un Choice sobre el arbol del usuario): p50
**307 ms** (714, 279, 277, 366, 307). No esta en la tabla porque no hace el
mismo trabajo, y TypeSafe no es proveedor de AI Gateway, asi que tampoco se
puede enrutar por ahi. Esta medido porque la pregunta que siempre aparece es
"no sera Jev lo lento", y es el 6% del total.
