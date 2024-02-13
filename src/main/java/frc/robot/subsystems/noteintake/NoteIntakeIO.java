package frc.robot.subsystems.noteintake;

import org.littletonrobotics.junction.AutoLog;

public interface NoteIntakeIO {
  @AutoLog
  public static class NoteIntakeIOInputs {
    public double velocityRPMs = 0.0;
    public boolean hasNote = false;
    public double appliedVolts = 0.0;
    public double currentAmps = 0.0;
  }
  /** Updates the set of loggable inputs. */
  public default void updateInputs(NoteIntakeIOInputs inputs) {}

  /** Set the motor voltage */
  public default void setVoltage(double volts) {}

  /** Enable or disable brake mode on the motors. */
  public default void setBrakeMode(boolean brake) {}
}
