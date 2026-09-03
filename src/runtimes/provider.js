// Sunshine Terminal VirtualMachineProvider — interface + shared result shapes.
// Engines (debian.js, microdroid.js) implement this contract; vm.js selects.
// All operations return plain data, never throw for expected failures.

export const PROVIDER_IDS = {
  debian: 'debian',
  microdroid: 'microdroid',
  none: 'none',
};

// Capability: what probe() returns.
// { id, label, available, reason, remediation, details }
export function capability({ id, label, available, reason = null, remediation = null, details = {} }) {
  return { id, label, available, reason, remediation, details };
}

// Operation result: { ok, ...payload }. ok:false always carries reason.
export function okResult(payload = {}) {
  return { ok: true, ...payload };
}

export function failResult(reason, extra = {}) {
  return { ok: false, reason, ...extra };
}

// Validate that an engine implements the contract. Throws on misuse (programmer error).
export function defineProvider(engine) {
  const required = ['id', 'label', 'probe', 'exec', 'shell', 'boot', 'shutdown', 'logs'];
  for (const key of required) {
    if (engine[key] === undefined || (typeof engine[key] !== 'function' && key !== 'id' && key !== 'label')) {
      throw new Error(`VirtualMachineProvider "${engine.id || '?'}": missing "${key}"`);
    }
  }
  return engine;
}

// Fallback when no engine is usable: every op reports why, honestly.
export function unavailableProvider(reasons = []) {
  return defineProvider({
    id: PROVIDER_IDS.none,
    label: 'No AVF provider available',
    async probe() {
      return capability({
        id: PROVIDER_IDS.none,
        label: 'No AVF provider available',
        available: false,
        reason: 'no-provider',
        remediation: 'Provision a Debian guest image or enable stock AVF (com.android.virt).',
        details: { reasons },
      });
    },
    async exec() {
      return failResult('no-provider', { remediation: 'No AVF guest is reachable. See `scli vm status`.' });
    },
    async shell() {
      return failResult('no-provider', { remediation: 'No AVF guest is reachable. See `scli vm status`.' });
    },
    async boot() {
      return failResult('no-provider', { remediation: 'Nothing to boot: no provider available.' });
    },
    async shutdown() {
      return failResult('no-provider', { remediation: 'Nothing to shut down.' });
    },
    async logs() {
      return failResult('no-provider', { remediation: 'No VM logs exist.' });
    },
  });
}
