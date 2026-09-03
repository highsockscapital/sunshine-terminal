// Sunshine Terminal thermal & battery throttling — host-side enforcement.
// Fanless mobile SoCs throttle aggressively under sustained load, so the
// CLI watches battery + skin temperature and degrades gracefully:
// throttled boots get fewer CPUs / less RAM, the crosvm host process is
// reniced, and every surface reports the Power Saver state.
// Signals are best-effort sysfs reads (no root, no Termux:API required);
// absent sensors → { level:'unknown', throttled:false } (never block).
import fs from 'fs/promises';

export const THERMAL_LEVELS = {
  OK: 'ok',
  WARM: 'warm',
  SEVERE: 'severe',
  UNKNOWN: 'unknown',
};

export const DEFAULT_THERMAL_CONFIG = {
  batteryLowPct: 15,
  warmTempC: 43,
  severeTempC: 48,
  degradedCpus: 1,
  degradedMemoryMb: 1024,
  renice: 10,
};

const BATTERY_CAPACITY_PATH = '/sys/class/power_supply/battery/capacity';
const THERMAL_ZONE_GLOB = [0, 1, 2, 3, 4, 5, 6, 7].map((i) => `/sys/class/thermal/thermal_zone${i}/temp`);

// Injectable sys surface: { readFile(path) }. Tests fake it; live uses fs.
export const liveThermalSys = {
  readFile: (p) => fs.readFile(p, 'utf8'),
};

async function readNumber(sys, filePath) {
  try {
    const raw = await sys.readFile(filePath);
    const n = Number(String(raw).trim());
    return Number.isFinite(n) ? n : null;
  } catch {
    return null;
  }
}

export async function probeThermal(sys = liveThermalSys, config = {}) {
  const cfg = { ...DEFAULT_THERMAL_CONFIG, ...config };
  const batteryPct = await readNumber(sys, BATTERY_CAPACITY_PATH);

  let maxTempC = null;
  for (const zone of THERMAL_ZONE_GLOB) {
    const milli = await readNumber(sys, zone);
    if (milli !== null) {
      // thermal_zone temp is millidegree Celsius on standard kernels.
      const c = milli / 1000;
      maxTempC = maxTempC === null ? c : Math.max(maxTempC, c);
    }
  }

  let level = THERMAL_LEVELS.UNKNOWN;
  const reasons = [];
  if (maxTempC !== null) {
    if (maxTempC >= cfg.severeTempC) {
      level = THERMAL_LEVELS.SEVERE;
      reasons.push(`skin ${maxTempC.toFixed(0)}°C ≥ severe ${cfg.severeTempC}°C`);
    } else if (maxTempC >= cfg.warmTempC) {
      level = THERMAL_LEVELS.WARM;
      reasons.push(`skin ${maxTempC.toFixed(0)}°C ≥ warm ${cfg.warmTempC}°C`);
    } else {
      level = THERMAL_LEVELS.OK;
    }
  }
  let batteryLow = false;
  if (batteryPct !== null && batteryPct <= cfg.batteryLowPct) {
    batteryLow = true;
    reasons.push(`battery ${batteryPct}% ≤ ${cfg.batteryLowPct}%`);
  }

  const throttled = level === THERMAL_LEVELS.SEVERE || batteryLow;
  return {
    batteryPct, maxTempC, level, batteryLow, throttled,
    powerSaver: throttled,
    reasons,
    thresholds: { batteryLowPct: cfg.batteryLowPct, warmTempC: cfg.warmTempC, severeTempC: cfg.severeTempC },
  };
}

export function degradedResources(config, thermal) {
  if (!thermal.throttled) {
    return { cpus: config.cpus, memoryMb: config.memoryMb, degraded: false };
  }
  return {
    cpus: Math.min(config.cpus, config.thermal?.degradedCpus ?? DEFAULT_THERMAL_CONFIG.degradedCpus),
    memoryMb: Math.min(config.memoryMb, config.thermal?.degradedMemoryMb ?? DEFAULT_THERMAL_CONFIG.degradedMemoryMb),
    degraded: true,
  };
}
