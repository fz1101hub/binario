export type CompanionPlannedRoute = {
  id: number;
  name: string;
  origin: string;
  originStationCode?: string | null;
  destination: string;
  destinationStationCode?: string | null;
  travelDate: string;
  travelTime: string;
  plannedReminderMinutes: number | null;
};
