import { useQuery } from '@tanstack/react-query';
import { orgApi } from '@/api/endpoints';

export function useSystems() {
  return useQuery({
    queryKey: ['org', 'systems'],
    queryFn: orgApi.systems,
    staleTime: 60_000,
  });
}

export function useModules(systemId?: number) {
  return useQuery({
    queryKey: ['org', 'modules', systemId ?? 'all'],
    queryFn: () => orgApi.modules(systemId),
    staleTime: 60_000,
  });
}

export function useDatasources() {
  return useQuery({
    queryKey: ['org', 'datasources'],
    queryFn: orgApi.datasources,
    staleTime: 30_000,
  });
}

export function useDatasourceDetail(id?: number) {
  return useQuery({
    queryKey: ['org', 'datasource', id],
    queryFn: () => orgApi.datasource(id as number),
    enabled: typeof id === 'number' && id > 0,
    staleTime: 60_000,
  });
}
