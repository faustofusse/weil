# Modelos de embedding comparados

167 mensajes de `data/lab.db` que Jev ya juzgo (75 movimientos, 92
promociones). Son los que llevan palabras de plata: los dificiles, porque una
promocion con un precio adentro se parece a una compra para cualquier cosa
mas superficial que el significado. k=5.

| modelo | dims | separa | mov@1 | comercio@1 | comercio@3 | 1 texto p50 | lote de 64 | USD/1k |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| gemini-embedding-001/512 (hoy) | 512 | 98% | 97% | **90%** | 92% | 638 ms | 1.438 ms | $0.0030 |
| **embeddinggemma-300m** | 768 | 98% | 96% | 88% | 92% | 173-800 ms | 805 ms | **$0.0002** |
| qwen3-embedding-0.6b | 1024 | 92% | 95% | 88% | 92% | 1.024 ms | 5.078 ms | $0.0002 |
| bge-m3 | 1024 | 93% | 97% | **90%** | 92% | **499 ms** | 1.049 ms | $0.0002 |

- **separa**: kNN leave-one-out sobre el corpus entero. Para cada mensaje se
  le pregunta a sus 5 vecinos si es movimiento y gana la mayoria. Es
  recuperacion haciendo el trabajo que hace en la app, no un clasificador
  entrenado para eso.
- **mov@1**: dado un movimiento, el vecino mas cercano tambien es movimiento.
- **comercio@1 / @3**: la pregunta fina, la que importa. 52 consultas sobre 25
  comercios etiquetados **a mano** en `data/merchants.json`, leyendo los 75
  movimientos uno por uno (en los mails de Santander el comercio esta en el
  cuerpo HTML, no en el asunto). Se busca contra el corpus completo: una
  promocion primera cuenta como error. Los 12 mensajes que no nombran comercio
  — la notificacion de Santander dice solo "Pagaste U$S21,23" — quedan como
  distractores pero no se usan como consulta: ningun modelo puede acertar lo
  que el texto no dice.
- **USD/1k**: mil embeddings sueltos de ~20 tokens. Cloudflare cobra
  $0,0118/M contra $0,15/M de Google.

## Que dicen los numeros

**Empatan.** En la pregunta gruesa (98% contra 98%) y tambien en la fina:
90% contra 88% en comercio@1 son **un caso de 52**, y en comercio@3 los
cuatro dan exactamente 92%. La diferencia entre el modelo mas caro y el mas
barato de la tabla no se ve en estos datos.

**Fallan en los mismos casos**, que es el dato que cierra la discusion:

    boca-juniors -> promo    "Entradas Boca Juniors Bot"
    tuenti       -> promo    "TUENTI"
    tuenti       -> coto     "Pagaste $2.500,00"
    boca-juniors -> ml       "Pagaste $20.000,00"
    anomaly      -> apple    "Aviso de débito automático"

No es falta de modelo: el aviso de Santander **no nombra el comercio**, asi
que "Pagaste $2.500,00" de TUENTI y uno de COTO son el mismo texto salvo el
numero. La unica forma de arreglarlo es darle mas contexto a la frase que se
embebe, y eso lo decide el lector, no el embedder — es la misma discusion del
campo `normalized`.

**Los 1024 dims no compran nada**: qwen3 es el que peor separa (92%) y bge-m3
tampoco gana. Y pesan: el doble en `F32_BLOB`, recorridos enteros en cada
escaneo exacto del telefono.

**El precio es ruido**: a 24.000 notificaciones, siete centavos contra medio.

**Entonces la decision no es por calidad ni por plata, es por donde corre.**
Gemma-300m es el unico de los cuatro que entra en el telefono (int4, <200 MB
de RAM, es para lo que Google lo hizo). Si el embedding es local: la captura
embebe sola en segundo plano, la etapa 2 deja de necesitar un viaje al worker,
y el texto no sale del dispositivo para esa parte. Eso vale mas que los dos
puntos de comercio@1, que ademas son un caso.

Nota de medicion: la latencia de un texto suelto de Gemma dio 800 ms en una
corrida y 173 ms en la siguiente. Es la diferencia entre un modelo frio y uno
caliente en Workers AI, y es otra razon para no tomar decisiones finas con
estos numeros.
