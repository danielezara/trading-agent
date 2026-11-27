import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { AppRoutes } from "./App";

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AppRoutes />
    </MemoryRouter>,
  );
}

describe("dashboard skeleton", () => {
  it("shows the demo banner and observation state", () => {
    renderAt("/");
    expect(screen.getByText("DEMO")).toBeInTheDocument();
    expect(screen.getByText("OBSERVATION")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Panoramica" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /compra|vendi|buy|sell/i })).not.toBeInTheDocument();
  });

  it("keeps chat read only", () => {
    renderAt("/chat");
    const input = screen.getByLabelText("Messaggio");
    expect(input).toBeDisabled();
    expect(screen.getByText(/non crea ordini/i)).toBeInTheDocument();
  });

  it("has no order action on the portfolio route", () => {
    renderAt("/portafoglio");
    expect(screen.getByRole("heading", { name: "Portafoglio" })).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
});
