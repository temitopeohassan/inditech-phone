/**
 * Lock State Controller — deterministic finite state machine governing the
 * device financing lifecycle:
 *
 *   PENDING_ENROLLMENT -> ACTIVE -> GRACE -> SOFT_LOCKED -> HARD_LOCKED
 *                                  (any non-terminal) -> RELEASED
 */
export const DEVICE_STATES = ['PENDING_ENROLLMENT', 'ACTIVE', 'GRACE', 'SOFT_LOCKED', 'HARD_LOCKED', 'RELEASED'] as const;
export type DeviceState = (typeof DEVICE_STATES)[number];

export const DEVICE_EVENTS = [
  'ENROLL', // DPC completed provisioning
  'WARN', // payment due soon / missed -> grace warning
  'SOFT_LOCK', // restricted mode
  'LOCK', // full lockdown (manual, overdue escalation)
  'TAMPER_DETECTED',
  'OFFLINE_THRESHOLD_EXCEEDED',
  'UNLOCK', // payment received / manual unlock
  'RELEASE', // loan discharged — device offboarded
] as const;
export type DeviceEvent = (typeof DEVICE_EVENTS)[number];

const TRANSITIONS: Record<DeviceState, Partial<Record<DeviceEvent, DeviceState>>> = {
  PENDING_ENROLLMENT: { ENROLL: 'ACTIVE', RELEASE: 'RELEASED' },
  ACTIVE: {
    WARN: 'GRACE',
    SOFT_LOCK: 'SOFT_LOCKED',
    LOCK: 'HARD_LOCKED',
    TAMPER_DETECTED: 'HARD_LOCKED',
    OFFLINE_THRESHOLD_EXCEEDED: 'HARD_LOCKED',
    UNLOCK: 'ACTIVE',
    RELEASE: 'RELEASED',
    ENROLL: 'ACTIVE',
  },
  GRACE: {
    WARN: 'GRACE',
    SOFT_LOCK: 'SOFT_LOCKED',
    LOCK: 'HARD_LOCKED',
    TAMPER_DETECTED: 'HARD_LOCKED',
    OFFLINE_THRESHOLD_EXCEEDED: 'HARD_LOCKED',
    UNLOCK: 'ACTIVE',
    RELEASE: 'RELEASED',
  },
  SOFT_LOCKED: {
    SOFT_LOCK: 'SOFT_LOCKED',
    LOCK: 'HARD_LOCKED',
    TAMPER_DETECTED: 'HARD_LOCKED',
    OFFLINE_THRESHOLD_EXCEEDED: 'HARD_LOCKED',
    UNLOCK: 'ACTIVE',
    RELEASE: 'RELEASED',
  },
  HARD_LOCKED: {
    LOCK: 'HARD_LOCKED',
    TAMPER_DETECTED: 'HARD_LOCKED',
    OFFLINE_THRESHOLD_EXCEEDED: 'HARD_LOCKED',
    UNLOCK: 'ACTIVE',
    RELEASE: 'RELEASED',
  },
  RELEASED: {},
};

export class InvalidTransitionError extends Error {
  constructor(
    public readonly from: DeviceState,
    public readonly event: DeviceEvent,
  ) {
    super(`Invalid transition: ${event} is not allowed from ${from}`);
  }
}

export function canTransition(from: DeviceState, event: DeviceEvent): boolean {
  return TRANSITIONS[from][event] !== undefined;
}

export function nextState(from: DeviceState, event: DeviceEvent): DeviceState {
  const to = TRANSITIONS[from][event];
  if (!to) throw new InvalidTransitionError(from, event);
  return to;
}

/** Severity ordering used by the overdue evaluator to only ever escalate automatically. */
const SEVERITY: Record<DeviceState, number> = {
  PENDING_ENROLLMENT: -1,
  ACTIVE: 0,
  GRACE: 1,
  SOFT_LOCKED: 2,
  HARD_LOCKED: 3,
  RELEASED: -2,
};

export function isEscalation(from: DeviceState, to: DeviceState): boolean {
  return SEVERITY[to] > SEVERITY[from];
}

export function isLockedState(state: DeviceState): boolean {
  return state === 'SOFT_LOCKED' || state === 'HARD_LOCKED';
}

/** Maps a lifecycle event to the device command that enforces it on the handset. */
export type CommandAction = 'LOCK' | 'SOFT_LOCK' | 'WARN' | 'UNLOCK' | 'RELEASE';

export function commandForState(state: DeviceState): CommandAction {
  switch (state) {
    case 'HARD_LOCKED':
      return 'LOCK';
    case 'SOFT_LOCKED':
      return 'SOFT_LOCK';
    case 'GRACE':
      return 'WARN';
    case 'RELEASED':
      return 'RELEASE';
    default:
      return 'UNLOCK';
  }
}

export function eventForCommand(action: CommandAction): DeviceEvent {
  return action;
}

export const EVENT_TYPE_FOR_ACTION: Record<CommandAction, string> = {
  LOCK: 'LOCK_COMMAND',
  SOFT_LOCK: 'SOFT_LOCK_COMMAND',
  WARN: 'WARN_COMMAND',
  UNLOCK: 'UNLOCK_COMMAND',
  RELEASE: 'RELEASE_COMMAND',
};

/**
 * Computes the state a device *should* be in from its loan position and the
 * tenant enforcement policy. Returns null when the loan does not drive a change.
 */
export function evaluateDelinquency(params: {
  now: Date;
  nextDueAt: Date;
  amountDue: number;
  warningHoursBeforeLock: number;
  gracePeriodHours: number;
  softLockHours: number;
}): DeviceState {
  const { now, nextDueAt, amountDue, warningHoursBeforeLock, gracePeriodHours, softLockHours } = params;
  if (amountDue <= 0) return 'ACTIVE';
  const hoursPastDue = (now.getTime() - nextDueAt.getTime()) / 3_600_000;
  if (hoursPastDue >= gracePeriodHours + softLockHours) return 'HARD_LOCKED';
  if (hoursPastDue >= gracePeriodHours) return 'SOFT_LOCKED';
  if (hoursPastDue >= -warningHoursBeforeLock) return 'GRACE';
  return 'ACTIVE';
}
