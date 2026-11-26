import { useInfiniteQuery, useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';

import { ApiError, clusterKey, request } from '../../kernel/api/request.ts';
import type { components } from '../../kernel/api/schema.d.ts';
import type { GateVerdict } from '../../ui/capabilityGate.ts';

type Schemas = components['schemas'];

const root = (clusterId: string) => `/clusters/${clusterId}/kafka/schemas`;
const seg = encodeURIComponent;

/** The driver's capability for whether this cluster's registry is configured and reachable. */
export const REGISTRY_CAPABILITY = 'kafka.schema-registry';

export const schemaKeys = {
  all: (c: string) => clusterKey(c, 'kafka', 'schemas'),
  subjects: (c: string, q: string) => clusterKey(c, 'kafka', 'schemas', 'subjects', q),
  subject: (c: string, name: string) => clusterKey(c, 'kafka', 'schemas', 'subject', name),
  bindings: (c: string) => clusterKey(c, 'kafka', 'schemas', 'bindings'),
};

export type SchemaSubjectPage = Schemas['SchemaSubjectPage'];
export type SchemaSubjectRow = Schemas['SchemaSubjectRow'];
export type SchemaSubject = Schemas['SchemaSubject'];
export type SchemaPreview = Schemas['SchemaPreview'];
export type SchemaVersion = Schemas['SchemaVersionView'];
export type SchemaBinding = Schemas['SchemaBinding'];
export type SchemaBindingPage = Schemas['SchemaBindingPage'];

/** The wire types the registry accepts. Not a generated enum. */
export const SCHEMA_TYPES = ['AVRO', 'PROTOBUF', 'JSON'] as const;
/** The compatibility levels the registry accepts. Not a generated enum. */
export const COMPATIBILITY_LEVELS = [
  'BACKWARD',
  'BACKWARD_TRANSITIVE',
  'FORWARD',
  'FORWARD_TRANSITIVE',
  'FULL',
  'FULL_TRANSITIVE',
  'NONE',
] as const;

/** Pretty-print schema text when it is JSON. Anything else is left as the registry sent it. */
export function prettySchema(text: string): string {
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}

export const SCHEMA_TYPE_LABEL: Record<(typeof SCHEMA_TYPES)[number], string> = {
  AVRO: 'Avro',
  PROTOBUF: 'Protobuf',
  JSON: 'JSON Schema',
};

/**
 * The driver's own reason the registry cannot be used, or null when that is not known.
 * A finding wins; a 409 `schema-unavailable` is the same sentence at call time. Never a sentence of our own.
 */
export function registryReason(
  findings: readonly { capability: string; status: string; reason: string }[] | undefined,
  error: unknown,
): string | null {
  const finding = findings?.find((item) => item.capability === REGISTRY_CAPABILITY);
  if (finding?.status === 'UNAVAILABLE') return finding.reason || null;
  if (error instanceof ApiError && error.status === 409 && error.type.endsWith('schema-unavailable')) {
    return error.message;
  }
  return null;
}

/** Write actions stay visible. Unknown stays enabled. A refusal names the driver's reason, then `schema:write`. */
export function schemaWriteGate(permitted: boolean, loading: boolean, unavailable: string | null): GateVerdict {
  // A known refusal blocks even while grants are still loading. Unknown stays enabled.
  if (unavailable) return { kind: 'blocked', reason: unavailable };
  if (loading) return { kind: 'allowed', uncertain: false };
  if (!permitted) {
    return {
      kind: 'blocked',
      reason: 'You do not have schema:write on this cluster. An administrator can grant it in Settings → Roles.',
    };
  }
  return { kind: 'allowed', uncertain: false };
}

function page(path: string, cursor: string | undefined, q: string) {
  const sp = new URLSearchParams();
  if (q) sp.set('filter', q);
  if (cursor) sp.set('cursor', cursor);
  const query = sp.toString();
  return request<SchemaSubjectPage>(query ? `${path}?${query}` : path);
}

/** A page of subjects. `q` is sent as `filter`, which is the parameter the registry list takes. */
export function useSchemaSubjects(clusterId: string, q: string) {
  return useInfiniteQuery({
    queryKey: schemaKeys.subjects(clusterId, q),
    queryFn: ({ pageParam }) => page(root(clusterId), pageParam, q),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.next ?? undefined,
  });
}

export function useSchemaSubject(clusterId: string, subject: string): UseQueryResult<SchemaSubject, ApiError> {
  return useQuery({
    queryKey: schemaKeys.subject(clusterId, subject),
    queryFn: () => request<SchemaSubject>(`${root(clusterId)}/${seg(subject)}`),
  });
}

export function useSchemaBindings(clusterId: string): UseQueryResult<SchemaBindingPage, ApiError> {
  return useQuery({
    queryKey: schemaKeys.bindings(clusterId),
    queryFn: () => request<SchemaBindingPage>(`${root(clusterId)}/bindings`),
  });
}

function invalidate(clusterId: string) {
  return (qc: ReturnType<typeof useQueryClient>) => () => {
    void qc.invalidateQueries({ queryKey: schemaKeys.all(clusterId) });
  };
}

export function usePreviewRegister(clusterId: string) {
  return useMutation<SchemaPreview, ApiError, { subject: string; type: string; schema: string }>({
    mutationFn: (body) =>
      request(`${root(clusterId)}:register-preview`, { method: 'POST', body: JSON.stringify(body) }),
  });
}

export function useRegister(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<SchemaVersion, ApiError, { subject: string; type: string; schema: string; etag: string }>({
    mutationFn: (body) => request(`${root(clusterId)}:register`, { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: invalidate(clusterId)(qc),
  });
}

export function usePreviewCompatibility(clusterId: string, subject: string) {
  return useMutation<SchemaPreview, ApiError, { level: string }>({
    mutationFn: (body) =>
      request(`${root(clusterId)}/${seg(subject)}:compatibility-preview`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
  });
}

export function useSetCompatibility(clusterId: string, subject: string) {
  const qc = useQueryClient();
  return useMutation<string, ApiError, { level: string; etag: string }>({
    mutationFn: (body) =>
      request(`${root(clusterId)}/${seg(subject)}:compatibility`, { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: invalidate(clusterId)(qc),
  });
}

export function usePreviewDefaultCompatibility(clusterId: string) {
  return useMutation<SchemaPreview, ApiError, { level: string }>({
    mutationFn: (body) =>
      request(`${root(clusterId)}:default-compatibility-preview`, { method: 'POST', body: JSON.stringify(body) }),
  });
}

export function useSetDefaultCompatibility(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<string, ApiError, { level: string; etag: string }>({
    mutationFn: (body) =>
      request(`${root(clusterId)}:default-compatibility`, { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: invalidate(clusterId)(qc),
  });
}

export function usePreviewDelete(clusterId: string, subject: string) {
  return useMutation<SchemaPreview, ApiError, { permanent: boolean }>({
    mutationFn: (body) =>
      request(`${root(clusterId)}/${seg(subject)}:delete-preview`, { method: 'POST', body: JSON.stringify(body) }),
  });
}

export function useDeleteSubject(clusterId: string, subject: string) {
  const qc = useQueryClient();
  return useMutation<void, ApiError, { permanent: boolean; etag: string }>({
    mutationFn: ({ permanent, etag }) =>
      request(`${root(clusterId)}/${seg(subject)}?permanent=${permanent}`, {
        method: 'DELETE',
        headers: { 'If-Match': etag },
      }),
    onSuccess: invalidate(clusterId)(qc),
  });
}

export function usePreviewBinding(clusterId: string) {
  return useMutation<SchemaPreview, ApiError, { topic: string; side: string; serde: string }>({
    mutationFn: (body) =>
      request(`${root(clusterId)}/bindings:preview`, { method: 'POST', body: JSON.stringify(body) }),
  });
}

export function useApplyBinding(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<SchemaBinding, ApiError, { topic: string; side: string; serde: string; etag: string }>({
    mutationFn: (body) => request(`${root(clusterId)}/bindings`, { method: 'PUT', body: JSON.stringify(body) }),
    onSuccess: invalidate(clusterId)(qc),
  });
}
