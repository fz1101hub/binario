import { Toaster } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";
import { lazy, Suspense, type ReactNode } from "react";
import { Route, Switch } from "wouter";
import ErrorBoundary from "./components/ErrorBoundary";
import { ThemeProvider } from "./contexts/ThemeContext";

const Home = lazy(() => import("./pages/Home"));
const RouteDetail = lazy(() => import("./pages/RouteDetail"));
const DataTransparency = lazy(() => import("./pages/DataTransparency"));
const DataGuide = lazy(() => import("./pages/DataGuide"));
const Agenda = lazy(() => import("./pages/Agenda"));
const StationBoard = lazy(() => import("./pages/StationBoard"));
const NotFound = lazy(() => import("./pages/NotFound"));

function LazyPage({ children }: { children: ReactNode }) {
  return <Suspense fallback={<main className="min-h-screen bg-background p-6 text-sm text-muted-foreground" aria-live="polite">Carico Binario…</main>}>{children}</Suspense>;
}

function HomeRoute() { return <LazyPage><Home /></LazyPage>; }
function AgendaRoute() { return <LazyPage><Agenda /></LazyPage>; }
function RouteDetailRoute() { return <LazyPage><RouteDetail /></LazyPage>; }
function DataGuideRoute() { return <LazyPage><DataGuide /></LazyPage>; }
function DataTransparencyRoute() { return <LazyPage><DataTransparency /></LazyPage>; }
function NotFoundRoute() { return <LazyPage><NotFound /></LazyPage>; }

function StationBoardRoute() {
  return <LazyPage><StationBoard /></LazyPage>;
}

function Router() {
  // make sure to consider if you need authentication for certain routes
  return (
    <Switch>
      <Route path={"/"} component={HomeRoute} />
      <Route path={"/agenda"} component={AgendaRoute} />
      <Route path={"/viaggi/:id"} component={RouteDetailRoute} />
      <Route path={"/guida"} component={DataGuideRoute} />
      <Route path={"/trasparenza-dati"} component={DataTransparencyRoute} />
      <Route path={"/stazioni"} component={StationBoardRoute} />
      <Route path={"/404"} component={NotFoundRoute} />
      {/* Final fallback route */}
      <Route component={NotFoundRoute} />
    </Switch>
  );
}

// NOTE: About Theme
// - First choose a default theme according to your design style (dark or light bg), than change color palette in index.css
//   to keep consistent foreground/background color across components
// - If you want to make theme switchable, pass `switchable` ThemeProvider and use `useTheme` hook

function App() {
  return (
    <ErrorBoundary>
      <ThemeProvider
        defaultTheme="light"
        switchable
      >
        <TooltipProvider>
          <Toaster />
          <Router />
        </TooltipProvider>
      </ThemeProvider>
    </ErrorBoundary>
  );
}

export default App;
