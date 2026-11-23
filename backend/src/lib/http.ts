import type { NextFunction, Request, RequestHandler, Response } from 'express';

/** Wraps async route handlers so rejections reach the error middleware (Express 4/5 compatible). */
export const ah =
  (fn: (req: Request, res: Response, next: NextFunction) => Promise<unknown>): RequestHandler =>
  (req, res, next) => {
    fn(req, res, next).catch(next);
  };

export function param(req: Request, name: string): string {
  const v = req.params[name];
  return Array.isArray(v) ? v[0]! : (v as string);
}
