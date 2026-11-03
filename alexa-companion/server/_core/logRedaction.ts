/**
 * Error descriptions that are safe to write to the server log.
 *
 * Database errors carry personal data in several places: drizzle's DrizzleQueryError message
 * appends every bound parameter ("params: 7,Roma Termini,Tivoli,Casa di Mario Rossi"), mysql2
 * errors keep the interpolated statement in `sql`, and driver messages quote values
 * ("Duplicate entry 'mario@example.com' for key ..."). Only the error class, the driver code
 * and the parameterised SQL text are kept; messages are kept only for the plain error classes
 * this code base throws with fixed text.
 */
export type LoggedError = {
  name: string;
  message?: string;
  code?: string | number;
  errno?: number;
  sqlState?: string;
  query?: string;
  cause?: LoggedError;
};

const MAX_QUERY_LENGTH = 1_000;
const MAX_MESSAGE_LENGTH = 300;
const MAX_DEPTH = 3;
// SyntaxError is excluded on purpose: JSON.parse quotes the offending input in its message.
const MESSAGE_SAFE_CLASSES = new Set(["Error", "TypeError", "RangeError", "ReferenceError"]);
const DRIVER_FIELDS = ["code", "errno", "sqlState", "sqlMessage", "sql", "syscall"] as const;

function truncate(value: string, length: number) {
  return value.length > length ? `${value.slice(0, length)}…` : value;
}

function className(error: object): string {
  const constructorName = (error as { constructor?: { name?: unknown } }).constructor?.name;
  if (typeof constructorName === "string" && constructorName && constructorName !== "Object")
    return constructorName;
  const name = (error as { name?: unknown }).name;
  return typeof name === "string" && name ? name : "Error";
}

function isDrizzleQueryError(error: object): error is { query: string; params: unknown[]; cause?: unknown } {
  return (
    typeof (error as { query?: unknown }).query === "string" &&
    Array.isArray((error as { params?: unknown }).params)
  );
}

function driverDetails(error: object): Pick<LoggedError, "code" | "errno" | "sqlState"> {
  const { code, errno, sqlState } = error as { code?: unknown; errno?: unknown; sqlState?: unknown };
  return {
    ...(typeof code === "string" || typeof code === "number" ? { code } : {}),
    ...(typeof errno === "number" ? { errno } : {}),
    ...(typeof sqlState === "string" ? { sqlState } : {}),
  };
}

export function describeErrorForLog(error: unknown, depth = 0): LoggedError {
  if (error === null || typeof error !== "object") return { name: typeof error };
  const cause = (error as { cause?: unknown }).cause;
  const nestedCause =
    cause !== undefined && depth < MAX_DEPTH ? { cause: describeErrorForLog(cause, depth + 1) } : {};

  if (isDrizzleQueryError(error))
    return {
      name: "DrizzleQueryError",
      // The statement as drizzle prepared it: placeholders only, the values are in `params`.
      query: truncate(error.query, MAX_QUERY_LENGTH),
      ...nestedCause,
    };

  const name = className(error);
  const message = (error as { message?: unknown }).message;
  if (name === "TRPCError") {
    // A TRPCError built from a thrown error copies that error's message: describe the cause.
    if (cause !== undefined && depth < MAX_DEPTH) return describeErrorForLog(cause, depth + 1);
    return typeof message === "string" ? { name, message: truncate(message, MAX_MESSAGE_LENGTH) } : { name };
  }
  if (DRIVER_FIELDS.some(field => field in error))
    return { name, ...driverDetails(error), ...nestedCause };

  return {
    name,
    ...(MESSAGE_SAFE_CLASSES.has(name) && typeof message === "string"
      ? { message: truncate(message, MAX_MESSAGE_LENGTH) }
      : {}),
    ...nestedCause,
  };
}
