import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Home, Route, TrainFront } from "lucide-react";
import { useLocation } from "wouter";

export default function NotFound() {
  const [, setLocation] = useLocation();

  const handleGoHome = () => {
    setLocation("/");
  };

  return (
    <main className="binario-page flex min-h-screen w-full items-center justify-center bg-slate-950 px-4 py-8 text-slate-50">
      <Card className="w-full max-w-lg border-slate-700 bg-slate-900 text-slate-50 shadow-2xl shadow-slate-950/30">
        <CardContent className="py-8 text-center sm:py-10">
          <div className="flex justify-center mb-6">
            <div className="relative rounded-3xl border border-cyan-300/30 bg-cyan-300/10 p-5">
              <div className="absolute inset-0 rounded-3xl bg-cyan-300/10 motion-safe:animate-pulse" />
              <TrainFront className="relative h-12 w-12 text-cyan-200" aria-hidden="true" />
            </div>
          </div>

          <p className="text-xs font-bold uppercase tracking-[0.18em] text-cyan-200">Binario · percorso non trovato</p>
          <h1 className="mt-3 text-4xl font-black tracking-tight text-white">404</h1>

          <h2 className="mt-2 text-xl font-semibold text-slate-100">
            Questo binario non è disponibile
          </h2>

          <p className="mx-auto mt-4 max-w-sm text-sm leading-6 text-slate-300">
            Il collegamento potrebbe essere incompleto, non più disponibile o riferito a una tratta che non esiste in questo browser.
          </p>

          <div
            id="not-found-button-group"
            className="mt-7 flex flex-col justify-center gap-3 sm:flex-row"
          >
            <Button
              onClick={handleGoHome}
              className="bg-cyan-300 px-6 py-2.5 text-slate-950 hover:bg-cyan-200"
            >
              <Home className="w-4 h-4 mr-2" />
              Torna a Binario
            </Button>
            <Button type="button" variant="outline" onClick={() => setLocation("/trasparenza-dati")} className="border-slate-600 bg-transparent text-slate-100 hover:bg-slate-800 hover:text-white"><Route className="mr-2 h-4 w-4" />Stato dei dati</Button>
          </div>
        </CardContent>
      </Card>
    </main>
  );
}
