import { describe, expect, it } from 'vitest';
import { canTransition, commandForState, evaluateDelinquency, isEscalation, nextState } from '../src/domain/stateMachine.js';

describe('lock lifecycle FSM', () => {
  it('follows the progressive lockdown path', () => {
    let s = nextState('PENDING_ENROLLMENT', 'ENROLL');
    expect(s).toBe('ACTIVE');
    s = nextState(s, 'WARN');
    expect(s).toBe('GRACE');
    s = nextState(s, 'SOFT_LOCK');
    expect(s).toBe('SOFT_LOCKED');
    s = nextState(s, 'LOCK');
    expect(s).toBe('HARD_LOCKED');
    s = nextState(s, 'UNLOCK');
    expect(s).toBe('ACTIVE');
  });

  it('treats RELEASED as terminal', () => {
    expect(nextState('HARD_LOCKED', 'RELEASE')).toBe('RELEASED');
    expect(canTransition('RELEASED', 'LOCK')).toBe(false);
    expect(() => nextState('RELEASED', 'UNLOCK')).toThrow(/Invalid transition/);
  });

  it('does not allow de-escalating a hard lock to a warning', () => {
    expect(canTransition('HARD_LOCKED', 'WARN')).toBe(false);
    expect(canTransition('SOFT_LOCKED', 'WARN')).toBe(false);
  });

  it('tamper and offline breaches always hard lock', () => {
    for (const s of ['ACTIVE', 'GRACE', 'SOFT_LOCKED'] as const) {
      expect(nextState(s, 'TAMPER_DETECTED')).toBe('HARD_LOCKED');
      expect(nextState(s, 'OFFLINE_THRESHOLD_EXCEEDED')).toBe('HARD_LOCKED');
    }
  });

  it('maps states to device commands', () => {
    expect(commandForState('HARD_LOCKED')).toBe('LOCK');
    expect(commandForState('SOFT_LOCKED')).toBe('SOFT_LOCK');
    expect(commandForState('GRACE')).toBe('WARN');
    expect(commandForState('ACTIVE')).toBe('UNLOCK');
    expect(isEscalation('ACTIVE', 'GRACE')).toBe(true);
    expect(isEscalation('HARD_LOCKED', 'SOFT_LOCKED')).toBe(false);
  });
});

describe('delinquency evaluation', () => {
  const due = new Date('2024-10-15T00:00:00Z');
  const policy = { warningHoursBeforeLock: 24, gracePeriodHours: 72, softLockHours: 48 };
  const at = (hoursFromDue: number) => new Date(due.getTime() + hoursFromDue * 3_600_000);

  it.each([
    [-48, 'ACTIVE'],
    [-12, 'GRACE'],
    [10, 'GRACE'],
    [72, 'SOFT_LOCKED'],
    [119, 'SOFT_LOCKED'],
    [120, 'HARD_LOCKED'],
    [500, 'HARD_LOCKED'],
  ])('%i hours from due date -> %s', (h, expected) => {
    expect(evaluateDelinquency({ now: at(h as number), nextDueAt: due, amountDue: 45, ...policy })).toBe(expected);
  });

  it('nothing due means active', () => {
    expect(evaluateDelinquency({ now: at(500), nextDueAt: due, amountDue: 0, ...policy })).toBe('ACTIVE');
  });
});
