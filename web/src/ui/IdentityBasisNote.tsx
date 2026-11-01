import { Notice } from './Notice.tsx';

/** How a preview's etag tells the resource it previewed from a recreated one (`INCARNATION` is the safe default). */
export type IdentityBasis = 'INCARNATION' | 'CONFIGURATION';

/**
 * Says, in a confirmation, that the system cannot tell a recreated resource apart: the preview was taken of a
 * configuration, so a resource deleted and created again with the same configuration before the action runs
 * receives it. Shown only for a `CONFIGURATION` basis; every other basis renders nothing. The wording names no
 * system, so any system whose etag rests on configuration reuses it.
 */
export function IdentityBasisNote({ basis }: Readonly<{ basis: IdentityBasis | null | undefined }>) {
  if (basis !== 'CONFIGURATION') return null;
  return (
    <Notice tone="info" title="A recreated resource is not told apart">
      This system does not tell a recreated resource apart: if it is deleted and created again with the same
      configuration before this runs, this applies to the new one.
    </Notice>
  );
}
