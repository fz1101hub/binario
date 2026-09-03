type AttemptWindow = { startedAtUtcMs: number; count: number };

export class PairingAttemptGuard {
  private readonly attempts = new Map<string, AttemptWindow>();
  constructor(private readonly maxAttempts = 5, private readonly windowMs = 10 * 60_000) {}
  allow(clientKey: string, now = Date.now()) {
    this.attempts.forEach((value, key) => { if (value.startedAtUtcMs + this.windowMs <= now) this.attempts.delete(key); });
    const current = this.attempts.get(clientKey);
    if (!current || current.startedAtUtcMs + this.windowMs <= now) { this.attempts.set(clientKey, { startedAtUtcMs: now, count: 1 }); return true; }
    if (current.count >= this.maxAttempts) return false;
    current.count += 1;
    return true;
  }
}
