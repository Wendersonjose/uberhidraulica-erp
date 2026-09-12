import { Navigate, Outlet, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth/useAuth'
import { AppShell } from './components/AppShell'
import { LoginPage, PasswordChangePage } from './features/auth/AuthPages'
import { CustomerFormPage, CustomersPage } from './features/customers/Customers'
import { VehicleFormPage, VehiclesPage } from './features/vehicles/Vehicles'
import { ServiceFormPage, ServicesPage } from './features/services/Services'
import { WorkOrderDetailPage, WorkOrderFormPage, WorkOrdersPage } from './features/work-orders/WorkOrders'
import './App.css'
export function ProtectedRoute(){const{session,loading}=useAuth();if(loading)return <main className="center-state">Carregando sessão…</main>;if(!session)return <Navigate to="/login" replace/>;if(session.mustChangePassword)return <Navigate to="/alterar-senha" replace/>;return <Outlet/>}
export default function App(){const{session,loading}=useAuth();return <Routes><Route path="/login" element={loading?<main className="center-state">Carregando sessão…</main>:session?<Navigate to={session.mustChangePassword?'/alterar-senha':'/ordens-servico'} replace/>:<LoginPage/>}/><Route path="/alterar-senha" element={loading?<main className="center-state">Carregando sessão…</main>:!session?<Navigate to="/login" replace/>:<PasswordChangePage/>}/><Route element={<ProtectedRoute/>}><Route element={<AppShell/>}><Route path="/clientes" element={<CustomersPage/>}/><Route path="/clientes/novo/pf" element={<CustomerFormPage type="PF"/>}/><Route path="/clientes/novo/pj" element={<CustomerFormPage type="PJ"/>}/><Route path="/veiculos" element={<VehiclesPage/>}/><Route path="/veiculos/novo" element={<VehicleFormPage/>}/><Route path="/servicos" element={<ServicesPage/>}/><Route path="/servicos/novo" element={<ServiceFormPage/>}/><Route path="/ordens-servico" element={<WorkOrdersPage/>}/><Route path="/ordens-servico/nova" element={<WorkOrderFormPage/>}/><Route path="/ordens-servico/:id" element={<WorkOrderDetailPage/>}/></Route></Route><Route path="/" element={<Navigate to={session?'/ordens-servico':'/login'} replace/>}/><Route path="*" element={<Navigate to="/" replace/>}/></Routes>}
