import { useAuth } from "@/_core/hooks/useAuth";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarHeader,
  SidebarInset,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarProvider,
  SidebarTrigger,
  useSidebar,
} from "@/components/ui/sidebar";
import { startLogin } from "@/const";
import { useIsMobile } from "@/hooks/useMobile";
import { Accessibility, BookOpen, CalendarDays, LayoutDashboard, LogOut, Moon, PanelLeft, Sun } from "lucide-react";
import React, { CSSProperties, useEffect, useRef, useState } from "react";
import { useLocation } from "wouter";
import { DashboardLayoutSkeleton } from './DashboardLayoutSkeleton';
import { useTheme } from "../contexts/ThemeContext";
import { Button } from "./ui/button";

const menuItems = [
  { icon: LayoutDashboard, label: "Binario", path: "/" },
  { icon: CalendarDays, label: "Agenda", path: "/agenda" },
  { icon: BookOpen, label: "Guida dati", path: "/guida" },
];

const SIDEBAR_WIDTH_KEY = "sidebar-width";
const DEFAULT_WIDTH = 280;
const MIN_WIDTH = 200;
const MAX_WIDTH = 480;

export default function DashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const [sidebarWidth, setSidebarWidth] = useState(() => {
    const saved = localStorage.getItem(SIDEBAR_WIDTH_KEY);
    return saved ? parseInt(saved, 10) : DEFAULT_WIDTH;
  });
  const { loading, user } = useAuth();

  useEffect(() => {
    localStorage.setItem(SIDEBAR_WIDTH_KEY, sidebarWidth.toString());
  }, [sidebarWidth]);

  if (loading) {
    return <DashboardLayoutSkeleton />
  }

  if (!user) {
    return <PublicDashboardLayout>{children}</PublicDashboardLayout>;
  }

  return (
    <SidebarProvider
      style={
        {
          "--sidebar-width": `${sidebarWidth}px`,
        } as CSSProperties
      }
    >
      <DashboardLayoutContent setSidebarWidth={setSidebarWidth}>
        {children}
      </DashboardLayoutContent>
    </SidebarProvider>
  );
}

type DashboardLayoutContentProps = {
  children: React.ReactNode;
  setSidebarWidth: (width: number) => void;
};

export function PublicDashboardLayout({ children }: { children: React.ReactNode }) {
  const [, setLocation] = useLocation();
  return <div className="min-h-screen bg-background text-foreground"><SkipToContent /><header className="sticky top-0 z-40 border-b border-border bg-background/95 backdrop-blur"><div className="mx-auto flex min-h-16 max-w-6xl flex-wrap items-center justify-between gap-3 px-4 py-2 sm:px-6"><nav aria-label="Navigazione principale" className="flex items-center gap-1"><Button type="button" variant="ghost" className="font-bold tracking-tight" onClick={() => setLocation("/")}>Binario</Button><Button type="button" variant="ghost" size="sm" onClick={() => setLocation("/agenda")}>Agenda</Button><Button type="button" variant="ghost" size="sm" onClick={() => setLocation("/guida")}>Guida dati</Button></nav><div className="flex items-center gap-1"><ThemeToggleButton /><AccessibilityMenu /><Button type="button" variant="outline" size="sm" onClick={() => startLogin()}>Accedi facoltativamente</Button></div></div></header><main id="contenuto-principale" tabIndex={-1} className="p-4 sm:p-6">{children}</main></div>;
}

function SkipToContent() {
  return <a className="binario-skip-link" href="#contenuto-principale">Vai al contenuto principale</a>;
}

function ThemeToggleButton({ className = "" }: { className?: string }) {
  const { theme, toggleTheme } = useTheme();
  const nextThemeLabel = theme === "dark" ? "Attiva il tema chiaro" : "Attiva il tema scuro";

  return (
    <Button
      type="button"
      variant="ghost"
      size="icon"
      className={`h-11 w-11 shrink-0 rounded-xl ${className}`}
      onClick={toggleTheme}
      aria-label={nextThemeLabel}
      aria-pressed={theme === "dark"}
      title={nextThemeLabel}
    >
      {theme === "dark" ? <Sun className="h-4 w-4" aria-hidden="true" /> : <Moon className="h-4 w-4" aria-hidden="true" />}
      <span className="sr-only">{nextThemeLabel}</span>
    </Button>
  );
}

function AccessibilityMenu() {
  const { highContrast, reduceMotion, largeText, simpleMode, toggleHighContrast, toggleReduceMotion, toggleLargeText, toggleSimpleMode } = useTheme();
  return <DropdownMenu><DropdownMenuTrigger asChild><Button type="button" variant="ghost" size="icon" className="h-11 w-11 shrink-0 rounded-xl" aria-label="Apri preferenze di accessibilità" title="Accessibilità"><Accessibility className="h-4 w-4" aria-hidden="true" /></Button></DropdownMenuTrigger><DropdownMenuContent align="start" className="w-60"><DropdownMenuCheckboxItem checked={highContrast} onCheckedChange={toggleHighContrast}>Contrasto elevato</DropdownMenuCheckboxItem><DropdownMenuCheckboxItem checked={largeText} onCheckedChange={toggleLargeText}>Testo più grande</DropdownMenuCheckboxItem><DropdownMenuCheckboxItem checked={reduceMotion} onCheckedChange={toggleReduceMotion}>Riduci movimento</DropdownMenuCheckboxItem><DropdownMenuCheckboxItem checked={simpleMode} onCheckedChange={toggleSimpleMode}>Modalità semplificata</DropdownMenuCheckboxItem></DropdownMenuContent></DropdownMenu>;
}

function DashboardLayoutContent({
  children,
  setSidebarWidth,
}: DashboardLayoutContentProps) {
  const { user, logout } = useAuth();
  const [location, setLocation] = useLocation();
  const { state, toggleSidebar } = useSidebar();
  const isCollapsed = state === "collapsed";
  const [isResizing, setIsResizing] = useState(false);
  const sidebarRef = useRef<HTMLDivElement>(null);
  const activeMenuItem = menuItems.find(item => item.path === location);
  const isMobile = useIsMobile();

  useEffect(() => {
    if (isCollapsed) {
      setIsResizing(false);
    }
  }, [isCollapsed]);

  useEffect(() => {
    const handleMouseMove = (e: MouseEvent) => {
      if (!isResizing) return;

      const sidebarLeft = sidebarRef.current?.getBoundingClientRect().left ?? 0;
      const newWidth = e.clientX - sidebarLeft;
      if (newWidth >= MIN_WIDTH && newWidth <= MAX_WIDTH) {
        setSidebarWidth(newWidth);
      }
    };

    const handleMouseUp = () => {
      setIsResizing(false);
    };

    if (isResizing) {
      document.addEventListener("mousemove", handleMouseMove);
      document.addEventListener("mouseup", handleMouseUp);
      document.body.style.cursor = "col-resize";
      document.body.style.userSelect = "none";
    }

    return () => {
      document.removeEventListener("mousemove", handleMouseMove);
      document.removeEventListener("mouseup", handleMouseUp);
      document.body.style.cursor = "";
      document.body.style.userSelect = "";
    };
  }, [isResizing, setSidebarWidth]);

  return (
    <>
      <SkipToContent />
      <div className="relative" ref={sidebarRef}>
        <Sidebar
          collapsible="icon"
          className="border-r-0"
          disableTransition={isResizing}
        >
          <SidebarHeader className="h-16 justify-center">
            <div className="flex items-center gap-3 px-2 transition-all w-full">
              <button
                onClick={toggleSidebar}
                className="h-11 w-11 flex items-center justify-center hover:bg-accent rounded-xl transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-ring shrink-0"
                aria-label="Apri o chiudi la navigazione"
              >
                <PanelLeft className="h-4 w-4 text-muted-foreground" />
              </button>
              <ThemeToggleButton />
              <AccessibilityMenu />
              {!isCollapsed ? (
                <div className="flex items-center gap-2 min-w-0">
                  <span className="font-semibold tracking-tight truncate">
                    Binario
                  </span>
                </div>
              ) : null}
            </div>
          </SidebarHeader>

          <SidebarContent className="gap-0">
            <nav aria-label="Navigazione principale">
            <SidebarMenu className="px-2 py-1">
              {menuItems.map(item => {
                const isActive = location === item.path;
                return (
                  <SidebarMenuItem key={item.path}>
                    <SidebarMenuButton
                      isActive={isActive}
                      onClick={() => setLocation(item.path)}
                      tooltip={item.label}
                      className={`h-10 transition-all font-normal`}
                    >
                      <item.icon
                        className={`h-4 w-4 ${isActive ? "text-primary" : ""}`}
                      />
                      <span>{item.label}</span>
                    </SidebarMenuButton>
                  </SidebarMenuItem>
                );
              })}
            </SidebarMenu>
            </nav>
          </SidebarContent>

          <SidebarFooter className="p-3">
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <button className="flex items-center gap-3 rounded-lg px-1 py-1 hover:bg-accent/50 transition-colors w-full text-left group-data-[collapsible=icon]:justify-center focus:outline-none focus-visible:ring-2 focus-visible:ring-ring">
                  <Avatar className="h-9 w-9 border shrink-0">
                    <AvatarFallback className="text-xs font-medium">
                      {user?.name?.charAt(0).toUpperCase()}
                    </AvatarFallback>
                  </Avatar>
                  <div className="flex-1 min-w-0 group-data-[collapsible=icon]:hidden">
                    <p className="text-sm font-medium truncate leading-none">
                      {user?.name || "-"}
                    </p>
                    <p className="text-xs text-muted-foreground truncate mt-1.5">
                      {user?.email || "-"}
                    </p>
                  </div>
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="w-48">
                <DropdownMenuItem
                  onClick={logout}
                  className="cursor-pointer text-destructive focus:text-destructive"
                >
                  <LogOut className="mr-2 h-4 w-4" />
                  <span>Esci</span>
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </SidebarFooter>
        </Sidebar>
        <div
          className={`absolute top-0 right-0 w-1 h-full cursor-col-resize hover:bg-primary/20 transition-colors ${isCollapsed ? "hidden" : ""}`}
          onMouseDown={() => {
            if (isCollapsed) return;
            setIsResizing(true);
          }}
          style={{ zIndex: 50 }}
        />
      </div>

      <SidebarInset>
        {isMobile && (
            <div className="flex border-b h-14 items-center justify-between bg-background/95 px-2 backdrop-blur supports-[backdrop-filter]:backdrop-blur sticky top-0 z-40">
            <div className="flex items-center gap-2">
              <SidebarTrigger className="h-9 w-9 rounded-lg bg-background" />
              <div className="flex items-center gap-3">
                <div className="flex flex-col gap-1">
                  <span className="tracking-tight text-foreground">
                    {activeMenuItem?.label ?? "Menu"}
                  </span>
                </div>
              </div>
              <ThemeToggleButton />
              <AccessibilityMenu />
            </div>
          </div>
        )}
        <main id="contenuto-principale" tabIndex={-1} className="flex-1 p-4">{children}</main>
      </SidebarInset>
    </>
  );
}
