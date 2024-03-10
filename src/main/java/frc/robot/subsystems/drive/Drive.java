// Copyright 2021-2023 FRC 6328
// http://github.com/Mechanical-Advantage
//
// This program is free software; you can redistribute it and/or
// modify it under the terms of the GNU General Public License
// version 3 as published by the Free Software Foundation or
// available in the root directory of this project.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.

package frc.robot.subsystems.drive;

import com.pathplanner.lib.auto.AutoBuilder;
import com.pathplanner.lib.pathfinding.Pathfinding;
import com.pathplanner.lib.util.HolonomicPathFollowerConfig;
import com.pathplanner.lib.util.PathPlannerLogging;
import com.pathplanner.lib.util.ReplanningConfig;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.math.geometry.Twist2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.math.kinematics.SwerveDriveKinematics;
import edu.wpi.first.math.kinematics.SwerveModulePosition;
import edu.wpi.first.math.kinematics.SwerveModuleState;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.DriverStation.Alliance;
import edu.wpi.first.wpilibj2.command.SubsystemBase;
import frc.robot.Constants; // TJG
import frc.robot.util.LocalADStarAK;
import org.littletonrobotics.junction.AutoLogOutput;
import org.littletonrobotics.junction.Logger;
import org.littletonrobotics.junction.networktables.LoggedDashboardNumber;

public class Drive extends SubsystemBase {
  private static final double MAX_LINEAR_SPEED = Constants.DriveConstants.kMAX_LINEAR_SPEED;
  private static final double TRACK_WIDTH_X = Constants.DriveConstants.kTRACK_WIDTH_X;
  private static final double TRACK_WIDTH_Y = Constants.DriveConstants.kTRACK_WIDTH_Y;
  private static final double DRIVE_BASE_RADIUS =
      Math.hypot(TRACK_WIDTH_X / 2.0, TRACK_WIDTH_Y / 2.0);
  private static final double MAX_ANGULAR_SPEED = MAX_LINEAR_SPEED / DRIVE_BASE_RADIUS;

  private final GyroIO gyroIO;
  private final GyroIOInputsAutoLogged gyroInputs = new GyroIOInputsAutoLogged();
  private final int kNumModules = 4;
  private final Module[] modules = new Module[kNumModules]; // FL, FR, BL, BR

  private SwerveDriveKinematics kinematics = new SwerveDriveKinematics(getModuleTranslations());
  private Pose2d pose = new Pose2d();
  private Rotation2d lastGyroRotation = new Rotation2d();

  private Twist2d fieldVelocity = new Twist2d(); // TJG
  private ChassisSpeeds setpoint = new ChassisSpeeds(); // TJG

  SwerveModulePosition[] positions =
      new SwerveModulePosition[] {
        new SwerveModulePosition(), new SwerveModulePosition(),
        new SwerveModulePosition(), new SwerveModulePosition()
      };

  private final LoggedDashboardNumber moduleTestIndex = // drive module to test with voltage ramp
      new LoggedDashboardNumber("Module Test Index (0-3)", 0);

  public Drive(
      GyroIO gyroIO,
      ModuleIO flModuleIO,
      ModuleIO frModuleIO,
      ModuleIO blModuleIO,
      ModuleIO brModuleIO) {
    this.gyroIO = gyroIO;
    modules[0] = new Module(flModuleIO, 0);
    modules[1] = new Module(frModuleIO, 1);
    modules[2] = new Module(blModuleIO, 2);
    modules[3] = new Module(brModuleIO, 3);

    // Configure AutoBuilder for PathPlanner
    AutoBuilder.configureHolonomic(
        this::getPose,
        this::setPose,
        () -> kinematics.toChassisSpeeds(getModuleStates()),
        this::runVelocity,
        new HolonomicPathFollowerConfig(
            MAX_LINEAR_SPEED, DRIVE_BASE_RADIUS, new ReplanningConfig()),
        () ->
            DriverStation.getAlliance().isPresent()
                && DriverStation.getAlliance().get() == Alliance.Red,
        this);
    Pathfinding.setPathfinder(new LocalADStarAK());
    PathPlannerLogging.setLogActivePathCallback(
        (activePath) -> {
          Logger.recordOutput(
              "Odometry/Trajectory", activePath.toArray(new Pose2d[activePath.size()]));
        });
    PathPlannerLogging.setLogTargetPoseCallback(
        (targetPose) -> {
          Logger.recordOutput("Odometry/TrajectorySetpoint", targetPose);
        });
  }

  public void periodic() {
    gyroIO.updateInputs(gyroInputs);
    Logger.processInputs("Drive/Gyro", gyroInputs);
    for (var module : modules) {
      module.periodic();
    }

    // Stop moving when disabled
    if (DriverStation.isDisabled()) {
      for (var module : modules) {
        module.stop();
      }
    }
    // Log empty setpoint states when disabled
    if (DriverStation.isDisabled()) {
      Logger.recordOutput("SwerveStates/Setpoints", new SwerveModuleState[] {});
      Logger.recordOutput("SwerveStates/SetpointsOptimized", new SwerveModuleState[] {});
    } else {
      // Calculate module setpoints
      ChassisSpeeds discreteSpeeds = ChassisSpeeds.discretize(setpoint, Constants.loopPeriodSecs);
      SwerveModuleState[] setpointStates = kinematics.toSwerveModuleStates(discreteSpeeds);
      SwerveDriveKinematics.desaturateWheelSpeeds(setpointStates, MAX_LINEAR_SPEED);

      // Send setpoints to modules
      SwerveModuleState[] optimizedSetpointStates = new SwerveModuleState[4];
      for (int i = 0; i < 4; i++) {
        // The module returns the optimized state, useful for logging
        optimizedSetpointStates[i] = modules[i].runSetpoint(setpointStates[i]);
      }

      // Log setpoint states
      Logger.recordOutput("SwerveStates/Setpoints", setpointStates);
      Logger.recordOutput("SwerveStates/SetpointsOptimized", optimizedSetpointStates);
    }

    // Log measured states
    SwerveModuleState[] measuredStates = new SwerveModuleState[4];
    for (int i = 0; i < kNumModules; i++) {
      measuredStates[i] = modules[i].getState();
    }
    Logger.recordOutput("SwerveStates/Measured", measuredStates);

    // Update odometry
    SwerveModulePosition[] wheelDeltas = new SwerveModulePosition[4];
    for (int i = 0; i < kNumModules; i++) {
      wheelDeltas[i] = modules[i].getPositionDelta();
    }

    // The twist represents the motion of the robot since the last
    // loop cycle in x, y, and theta based only on the modules,
    // without the gyro. The gyro is always disconnected in simulation.
    var twist = kinematics.toTwist2d(wheelDeltas);
    if (gyroInputs.connected) {
      // If the gyro is connected, replace the theta component of the twist
      // with the change in angle since the last loop cycle.
      Rotation2d currentGyroRotation = new Rotation2d(gyroInputs.yawPositionRad);
      twist =
          new Twist2d(twist.dx, twist.dy, currentGyroRotation.minus(lastGyroRotation).getRadians());
      lastGyroRotation = currentGyroRotation;
    } else {
      // no gyro in simulation, faking using odometry twist
      lastGyroRotation = new Rotation2d(twist.dtheta + lastGyroRotation.getRadians());
    }

    SwerveModulePosition[] wheelAbsolutes = new SwerveModulePosition[4];
    for (int i = 0; i < kNumModules; i++) {
      wheelAbsolutes[i] = modules[i].getPosition();
    }

    // TODO: put pose estimator back into use
    // updating the pose estimator
    // pose = poseEstimator.update(lastGyroRotation, wheelAbsolutes);

    // Previous method of updating pose:
    // Apply the twist (change since last loop cycle) to the current pose
    pose = pose.exp(twist);

    // Update field velocity
    ChassisSpeeds chassisSpeeds = kinematics.toChassisSpeeds(measuredStates);
    Translation2d linearFieldVelocity =
        new Translation2d(chassisSpeeds.vxMetersPerSecond, chassisSpeeds.vyMetersPerSecond)
            .rotateBy(getRotation());
    fieldVelocity =
        new Twist2d(
            linearFieldVelocity.getX(),
            linearFieldVelocity.getY(),
            gyroInputs.connected
                ? gyroInputs.yawVelocityRadPerSec
                : chassisSpeeds.omegaRadiansPerSecond);
  }

  /**
   * Runs the drive at the desired velocity.
   *
   * @param speeds Speeds in meters/sec
   */
  public void runVelocity(ChassisSpeeds speeds) {
    setpoint = speeds;
  }

  /** Stops the drive. */
  public void stop() {
    runVelocity(new ChassisSpeeds());
  }

  /**
   * Stops the drive and turns the modules to an X arrangement to resist movement. The modules will
   * return to their normal orientations the next time a nonzero velocity is requested.
   */
  public void stopWithX() {
    Rotation2d[] headings = new Rotation2d[4];
    for (int i = 0; i < 4; i++) {
      headings[i] = getModuleTranslations()[i].getAngle();
    }
    kinematics.resetHeadings(headings);
    stop();
  }

  /** Runs forwards at the commanded voltage. */
  public void runCharacterizationVolts(double volts) {
    for (int i = 0; i < 4; i++) {
      modules[i].runCharacterization(volts);
    }
  }

  /** Sets voltage ramp command for testing. */
  public void runDriveCommandRampVolts(double volts) {
    int moduleIndex = (int) moduleTestIndex.get();

    for (int i = 0; i < 4; i++) {
      if (i == moduleIndex) modules[moduleIndex].runDriveMotor(volts);
      else modules[i].stop();
    }
  }

  /** Sets voltage ramp command for testing. */
  public void runTurnCommandRampVolts(double volts) {
    int moduleIndex = (int) moduleTestIndex.get();

    for (int i = 0; i < 4; i++) {
      if (i == moduleIndex) modules[moduleIndex].runTurnMotor(volts);
      else modules[i].stop();
    }
  }

  /** Returns the average drive velocity in radians/sec. */
  public double getCharacterizationVelocity() {
    double driveVelocityAverage = 0.0;
    for (var module : modules) {
      driveVelocityAverage += module.getCharacterizationVelocity();
    }
    return driveVelocityAverage / 4.0;
  }

  /** Returns the module states (turn angles and drive velocities) for all of the modules. */
  @AutoLogOutput(key = "SwerveStates/Measured")
  private SwerveModuleState[] getModuleStates() {
    SwerveModuleState[] states = new SwerveModuleState[4];
    for (int i = 0; i < 4; i++) {
      states[i] = modules[i].getState();
    }
    return states;
  }

  /** Returns the current odometry pose. */
  @AutoLogOutput(key = "Odometry/Robot")
  public Pose2d getPose() {
    return pose;
  }

  /** Returns the current odometry rotation. */
  @AutoLogOutput
  public Rotation2d getRotation() {
    return pose.getRotation();
  }

  /** Resets the current odometry pose. */
  public void setPose(Pose2d pose) {
    // TODO: make it actually offset the angle
    this.pose = pose;
  }

  public void resetFieldHeading() {
    gyroIO.resetIMU();
  }

  /** Returns the maximum linear speed in meters per sec. */
  public double getMaxLinearSpeedMetersPerSec() {
    return MAX_LINEAR_SPEED;
  }

  /** Returns the maximum angular speed in radians per sec. */
  public double getMaxAngularSpeedRadPerSec() {
    return MAX_ANGULAR_SPEED;
  }

  /** Returns an array of module translations. */
  public static Translation2d[] getModuleTranslations() {
    return new Translation2d[] {
      new Translation2d(TRACK_WIDTH_X / 2.0, TRACK_WIDTH_Y / 2.0),
      new Translation2d(TRACK_WIDTH_X / 2.0, -TRACK_WIDTH_Y / 2.0),
      new Translation2d(-TRACK_WIDTH_X / 2.0, TRACK_WIDTH_Y / 2.0),
      new Translation2d(-TRACK_WIDTH_X / 2.0, -TRACK_WIDTH_Y / 2.0)
    };
  }

  /**
   * TJG Returns the measured X, Y, and theta field velocities in meters per sec. The components of
   * the twist are velocities and NOT changes in position.
   */
  public Twist2d getFieldVelocity() {
    return fieldVelocity;
  }

  /** Returns the current yaw velocity (Z rotation) in radians per second. TJG */
  public double getYawVelocity() {
    return gyroInputs.yawVelocityRadPerSec;
  }

  @AutoLogOutput
  public double getYaw() {
    return pose.getRotation().getRadians();
  }
}
