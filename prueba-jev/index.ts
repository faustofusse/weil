import { noul, TypeSafeClient } from "@typesafe-ai/sdk";

const client = new TypeSafeClient();

async function isTransaction(notification: any): Promise<any> {
  const response = await client.systemOne({
    state: { notification },
    questions: {
      isTransaction: noul("Does this push notification represent a financial transaction?"),
    },
  });

  return response.answers.isTransaction.noul
}

const notifications = [
  { application: "Whatsapp", title: "Backup in progress", text: "Preparing backup (35%)", category: null },
  { application: "Mercado Pago", title: "Pagaste $ 46.210 a Rappi", text: "Conocé más detalles.", category: null },
  { application: "Mercado Pago", title: "Tu pago fue aprobado", text: "Hiciste un pago por  $ 19200. Revisá el detalle de la operación.", category: null },
  { application: "Mercado Pago", title: "¡15% OFF en Carrefour! 😱", text: "Aprovechá hoy ¡Sin tope! Llená el carrito 🛒", category: null },
  { application: "Mercado Pago", title: "Recibiste $ 35.000", text: "Agostina Souto te envió dinero y ya está generando rendimientos en tu cuenta.", category: null },
];

for (const notification of notifications) {
  console.log(notification.title, await isTransaction(notification))
}
