# Modelos de embedding comparados

167 mensajes de `data/lab.db` que Jev ya juzgo (75 movimientos, 92
promociones). Son los que llevan palabras de plata: los dificiles, porque una
promocion con un precio adentro se parece a una compra para cualquier cosa
mas superficial que el significado. k=5.

| modelo | dims | separa | P@1 | P@3 | 1 texto p50 | lote de 64 | USD/1k |
| --- | --- | --- | --- | --- | --- | --- | --- |
| gemini-embedding-001/512 (hoy) | 512 | 95% | 97% | 99% | 644 ms | 1.508 ms | $0.0030 |
| **embeddinggemma-300m** | 768 | **97%** | 96% | 99% | 818 ms | 2.254 ms | **$0.0002** |
| qwen3-embedding-0.6b | 1024 | 93% | 93% | 97% | 2.039 ms | 5.415 ms | $0.0002 |
| bge-m3 | 1024 | 92% | **97%** | 99% | **492 ms** | **1.217 ms** | $0.0002 |

- **separa**: kNN leave-one-out sobre el corpus entero. Para cada mensaje se
  le pregunta a sus 5 vecinos si es movimiento y gana la mayoria. Es
  recuperacion haciendo el trabajo que hace en la app, no un clasificador
  entrenado para eso.
- **P@1 / P@3**: dado un movimiento, el vecino mas cercano tambien es
  movimiento? Ese es el precedente que la sugerencia sale a buscar, y una
  promocion apareciendo primera es la falla que importa.
- **USD/1k**: mil embeddings sueltos de ~20 tokens a los precios de los model
  pages. Los de Cloudflare cuestan $0,0118/M contra $0,15/M de Google.

## Que dicen los numeros

**Gemma-300m empata o gana, por la quinceava parte del precio.** 97% de
separacion contra 95%, P@1 96 contra 97 — con 167 casos, esa diferencia es
un mensaje. Que un modelo de 300M hecho para correr en un telefono le empate
a gemini-embedding-001 dice mas sobre la tarea que sobre los modelos: separar
"Pagaste $21.389 a Rappi" de "15% OFF en Carrefour" no necesita el modelo mas
grande del mundo.

**Los 1024 dims no compran nada.** qwen3 es el que peor separa (93%) y el mas
lento por lejos (2 s un texto); bge-m3 es el mas rapido (492 ms) y el mejor en
P@1, pero el que peor separa. Mas dimensiones tampoco salen gratis del otro
lado: ocupan el doble en `F32_BLOB` y el escaneo exacto en el telefono los
recorre todos.

**El precio es ruido en los dos casos.** $0,003 contra $0,0002 por mil
mensajes: a 24.000 notificaciones, siete centavos contra medio. Esto no se
decide por plata.

**Donde si hay diferencia es en el futuro.** Gemma-300m es el unico de los
cuatro que puede correr en el telefono (int4, <200 MB de RAM, es literalmente
para lo que Google lo hizo). Si el embedding se hace local, la captura embebe
sola en segundo plano, la etapa 2 deja de necesitar un viaje al worker y el
texto no sale del dispositivo para esa parte.

## Lo que esta medicion no dice

Mide separar movimientos de promociones. El uso real en la app es un escalon
mas fino: **dado un movimiento, traer los movimientos del mismo comercio**,
para que el precedente sirva de ejemplo. Para medir eso hace falta etiquetar
comercios a mano sobre estos 75 movimientos. Vale la pena antes de migrar:
los cuatro modelos estan arriba de 93% en la pregunta gruesa, asi que la que
va a decidir es la fina.
