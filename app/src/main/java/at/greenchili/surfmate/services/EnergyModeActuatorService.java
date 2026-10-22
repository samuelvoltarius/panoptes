package at.greenchili.surfmate.services;

/** Powertrain-only endpoint isolated from the existing vehicle actuators. */
public final class EnergyModeActuatorService extends VehicleActuatorService {
    @Override protected boolean supportsEnergyMode() {
        return true;
    }
}
