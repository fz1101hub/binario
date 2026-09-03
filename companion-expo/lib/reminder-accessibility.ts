export function deleteRoutePrompt(name: string) { return { title: "Eliminare tratta locale?", message: `Elimini “${name}” dal telefono. Non modifica dati ferroviari o dell’account.`, cancel: "Annulla", confirm: "Elimina" }; }
export function reminderFeedbackPoliteness(message: string) { return /non|errore|impossibile/i.test(message) ? "assertive" as const : "polite" as const; }
