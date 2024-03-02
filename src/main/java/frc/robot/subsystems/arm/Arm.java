package frc.robot.subsystems.arm;

import static frc.robot.Constants.ArmConstants.*;

import edu.wpi.first.math.controller.ArmFeedforward;
import edu.wpi.first.math.trajectory.TrapezoidProfile;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.Timer;
import edu.wpi.first.wpilibj2.command.SubsystemBase;
import org.littletonrobotics.junction.Logger;

public class Arm extends SubsystemBase {
  private ArmIO io;
  private final ArmIOInputsAutoLogged inputs = new ArmIOInputsAutoLogged();
  private double manuelInput;
  private double feedforward;

  private TrapezoidProfile motorProfile = new TrapezoidProfile(ARM_MOTION_CONSTRAINTS);
  private TrapezoidProfile.State setpoint = new TrapezoidProfile.State();
  private TrapezoidProfile.State goal = new TrapezoidProfile.State();
  private Timer timer;
  private ArmFeedforward ARM_FF = new ArmFeedforward(ARM_FF_KS, ARM_FF_KG, ARM_FF_KV, ARM_FF_KA);

  private static enum ArmState {
    kManuel,
    kGoToPos,
  };

  private static enum PistonState {
    kExtended,
    kRetracted
  };

  private ArmState armState;
  private PistonState pistonState;

  public Arm(ArmIO io) {
    System.out.println("[Init] Creating Arm");
    this.io = io;
    armState = ArmState.kManuel;
    pistonState = PistonState.kRetracted;

    timer = new Timer();
    timer.start();
    timer.reset();
  }

  @Override
  public void periodic() {
    io.updateInputs(inputs);
    Logger.processInputs("Arm", inputs);

    // Reset when disabled
    if (DriverStation.isDisabled()) {
      // manuelInput = 0.0;
      armState = ArmState.kManuel;
    }

    switch (armState) {
      case kManuel:
        double maxVelRadPerSecond = 0.5;
        this.feedforward = ARM_FF.calculate(inputs.angleRads, maxVelRadPerSecond * manuelInput);
        io.setVoltage(this.feedforward);
        break;
      case kGoToPos:
        setpoint = motorProfile.calculate(timer.get(), setpoint, goal);

        feedforward = ARM_FF.calculate(setpoint.position, setpoint.velocity);
        io.setVoltage(this.feedforward);
        break;
    }

    switch (pistonState) {
      case kExtended:
        io.extend();
        break;
      case kRetracted:
        io.retract();
        break;
    }
  }

  public boolean hasReachedTarget() {
    return motorProfile.isFinished(timer.get());
  }

  // called from run command
  public void setManuel(double manuelInput) {
    armState = ArmState.kManuel;
    this.manuelInput = manuelInput;
  }

  // assumption is that this is called once to set the target position, not continuously.
  public void setTargetPosToCurrent() {
    timer.reset();
    setpoint = new TrapezoidProfile.State(inputs.angleRads, inputs.velocityRadsPerSec);
    this.goal = new TrapezoidProfile.State(inputs.angleRads, 0.0);
  }

  // assumption is that this is called once to set the target position, not continuously.
  public void setTargetPos(double targetPos) {
    timer.reset();
    setpoint = new TrapezoidProfile.State(inputs.angleRads, inputs.velocityRadsPerSec);
    this.goal = new TrapezoidProfile.State(targetPos, 0.0);
  }

  // called from run command
  public void setGoToPos() {
    armState = ArmState.kGoToPos;
  }

  // called from instant command
  public void setExtended() {
    pistonState = PistonState.kExtended;
  }

  // called from instant command
  public void setRetracted() {
    pistonState = PistonState.kRetracted;
  }
}
