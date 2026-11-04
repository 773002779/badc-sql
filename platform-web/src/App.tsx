import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';
import { AppLayout } from '@/layout/AppLayout';
import { useAuth } from '@/auth/AuthProvider';
import { LoginPage } from '@/pages/LoginPage';
import { NotFoundPage } from '@/pages/NotFoundPage';
import { QueryPage } from '@/pages/QueryPage';
import { HistoryPage } from '@/pages/HistoryPage';
import { TicketListPage } from '@/pages/TicketListPage';
import { TicketCreatePage } from '@/pages/TicketCreatePage';
import { TicketDetailPage } from '@/pages/TicketDetailPage';
import { TempExecPage } from '@/pages/TempExecPage';
import { NotificationsPage } from '@/pages/NotificationsPage';
import { GrantsPage } from '@/pages/GrantsPage';
import { AuditPage } from '@/pages/AuditPage';
import { AdminPage } from '@/pages/AdminPage';

function RequireAuth({ children }: { children: ReactNode }) {
  const { authenticated } = useAuth();
  const location = useLocation();
  if (!authenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }
  return <>{children}</>;
}

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        path="/"
        element={
          <RequireAuth>
            <AppLayout />
          </RequireAuth>
        }
      >
        <Route index element={<Navigate to="/query" replace />} />
        <Route path="query" element={<QueryPage />} />
        <Route path="history" element={<HistoryPage />} />
        <Route path="tickets" element={<TicketListPage />} />
        <Route path="tickets/new" element={<TicketCreatePage />} />
        <Route path="tickets/:id" element={<TicketDetailPage />} />
        <Route path="temp-exec" element={<TempExecPage />} />
        <Route path="notifications" element={<NotificationsPage />} />
        <Route path="grants" element={<GrantsPage />} />
        <Route path="audit" element={<AuditPage />} />
        <Route path="admin" element={<AdminPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
