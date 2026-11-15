import { CircleMarker, MapContainer, Polyline, Popup, TileLayer, useMap } from "react-leaflet";
import { useEffect } from "react";
import type { LocationReport } from "./types";

type Props = { locations: LocationReport[]; compact?: boolean };

function FitRoute({ locations }: Props) {
  const map = useMap();
  useEffect(() => {
    if (!locations.length) return;
    if (locations.length === 1) {
      map.setView([locations[0].latitude, locations[0].longitude], 14, { animate: false });
      return;
    }
    map.fitBounds(locations.map((item) => [item.latitude, item.longitude]), { padding: [34, 34], maxZoom: 14 });
  }, [locations, map]);
  return null;
}

export default function MapPanel({ locations, compact = false }: Props) {
  const path = [...locations].reverse().map((item) => [item.latitude, item.longitude] as [number, number]);
  const center: [number, number] = locations.length
    ? [locations[0].latitude, locations[0].longitude]
    : [-28.0473, 26.2041];

  return (
    <div className={`map-frame${compact ? " map-frame-compact" : ""}`}>
      <MapContainer center={center} zoom={locations.length ? 12 : 5} scrollWheelZoom={!compact}>
        <TileLayer
          attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
          url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
        />
        <FitRoute locations={locations} />
        {path.length > 1 && <Polyline positions={path} pathOptions={{ color: "#5fbd8c", weight: 4, opacity: 0.82 }} />}
        {locations.map((point, index) => (
          <CircleMarker
            key={point.locationId}
            center={[point.latitude, point.longitude]}
            radius={index === 0 ? 9 : 6}
            pathOptions={{ color: "#f4f8f5", weight: 2, fillColor: index === 0 ? "#55c084" : "#377e5b", fillOpacity: 0.95 }}
          >
            <Popup>
              <strong>{index === 0 ? "Latest report" : "Recorded point"}</strong>
              <br />Accuracy: ±{Math.round(point.accuracyMeters)} m
              <br />{point.recordedAt?.toDate().toLocaleString() ?? "Time unavailable"}
            </Popup>
          </CircleMarker>
        ))}
      </MapContainer>
      {!locations.length && (
        <div className="map-empty">
          <span className="map-pin">⌖</span>
          <strong>No location reports yet</strong>
          <span>The map will center on your phone after its first report.</span>
        </div>
      )}
      <div className="map-caption">Map tiles © OpenStreetMap contributors</div>
    </div>
  );
}
