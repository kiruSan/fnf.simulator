package com.zuehlke.carrera.simulator.model;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import com.zuehlke.carrera.connection.TowardsPilotsConnection;
import com.zuehlke.carrera.relayapi.messages.*;
import com.zuehlke.carrera.simulator.config.SimulatorProperties;
import com.zuehlke.carrera.simulator.model.akka.AkkaUtils;
import com.zuehlke.carrera.simulator.model.akka.clock.StartClock;
import com.zuehlke.carrera.simulator.model.akka.clock.StopClock;
import com.zuehlke.carrera.simulator.model.akka.communication.NewsInterface;
import com.zuehlke.carrera.simulator.model.akka.messages.ActorRegistration;
import com.zuehlke.carrera.simulator.model.racetrack.TrackDesign;
import org.apache.commons.math3.distribution.RealDistribution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RaceTrackSimulatorSystem {
    private static final Logger LOG = LoggerFactory.getLogger(RaceTrackSimulatorSystem.class);
    private final String raceTrackId;
    private PilotInterface pilotChannel;
    private final NewsInterface newsChannel;
    private ActorSystem simulator;
    private ActorRef raceTrackActor;
    private ActorRef clockActor;

    public RaceTrackSimulatorSystem(String raceTrackId,
                                    PilotInterface pilotChannel,
                                    NewsInterface newsChannel,
                                    RealDistribution tickDistribution,
                                    SimulatorProperties properties) {
        if (raceTrackId == null) {
            throw new IllegalArgumentException("raceTrackId must not be null");
        }
        if (pilotChannel == null) {
            throw new IllegalArgumentException("pilotChannel must not be null");
        }
        if (newsChannel == null) {
            throw new IllegalArgumentException("newsChannel must not be null");
        }
        this.raceTrackId = raceTrackId;
        this.pilotChannel = pilotChannel;
        this.newsChannel = newsChannel;
        initializeActorSystem(tickDistribution, properties);
    }

    private void initializeActorSystem(RealDistribution tickDistribution, SimulatorProperties properties) {
        LOG.info("Starting racetrack simulator with mean " + (int) tickDistribution.getNumericalMean() + " ms");
        simulator = ActorSystem.create("Simulator");
        clockActor = createStopWatch(simulator, tickDistribution, newsChannel);
        raceTrackActor = createSimulationSystem(simulator, raceTrackId, properties, pilotChannel, newsChannel);
        clockActor.tell(new ActorRegistration(raceTrackActor), ActorRef.noSender());
    }

    private ActorRef createSimulationSystem(ActorSystem simulator, String raceTrackId, SimulatorProperties properties,
                                            PilotInterface pilotChannel, NewsInterface newsChannel) {
        RaceTrackSimulationActorCreator actorCreator = new RaceTrackSimulationActorCreator(raceTrackId, simulator,
                properties);
        return actorCreator.create(pilotChannel, newsChannel);
    }

    private ActorRef createStopWatch(ActorSystem simulator, RealDistribution tickDistribution, NewsInterface
            newsChannel) {
        ClockActorCreator actorCreator = new ClockActorCreator(simulator);
        return actorCreator.create(tickDistribution, newsChannel);
    }

    public void startClock() {
        // drives the whole simulator
        clockActor.tell(new StartClock(), ActorRef.noSender());
    }

    public void stopClock() {
        clockActor.tell(new StopClock(), ActorRef.noSender());
    }

    public void startRace(RaceStartMessage message) {
        startClock();
        raceTrackActor.tell(message, ActorRef.noSender());
    }

    public void stopRace(RaceStopMessage message) {
        stopClock();
        raceTrackActor.tell(message, ActorRef.noSender());
    }

    public void shutdown() {
        stopClock();
        simulator.shutdown();
    }

    public void setPower(PowerControl power) {
        raceTrackActor.tell(power, ActorRef.noSender());
    }

    public void powerup(int delta) {
        raceTrackActor.tell(new PowerChange(delta), ActorRef.noSender());
    }

    public void powerdown(int delta) {
        raceTrackActor.tell(new PowerChange(-delta), ActorRef.noSender());
    }

    public void reset() {
        raceTrackActor.tell(new Reset(), ActorRef.noSender());
    }

    public TrackDesign getTrackDesign() {
        return AkkaUtils.askActor(getClass(), TrackDesign.class, raceTrackActor, new QueryTrackDesign());
    }

    public TrackDesign selectDesign(String trackDesign) {
        // select design and make sure we don't return before it's done.
        return AkkaUtils.askActor(getClass(), TrackDesign.class, raceTrackActor, new QuerySelectDesign(trackDesign));
    }

    public void register (TowardsPilotsConnection connection) {

        this.pilotChannel = new PilotInterface() {
            @Override
            public void send(SensorEvent message) {
                connection.sendSensorEvent(message);
            }

            @Override
            public void send(VelocityMessage message) {
                connection.sendVelocity(message);
            }

            @Override
            public void send(PenaltyMessage message) {
                connection.sendPenalty(message);
            }

            @Override
            public void send(RoundTimeMessage message) {
                connection.sendRoundPassed(message);
            }

            @Override
            public void ensureConnection(String url) {
                connection.connect(url);
            }

        };

        raceTrackActor.tell ( pilotChannel, ActorRef.noSender() );
    }

    public void ensureConnection ( String url ) {
        pilotChannel.ensureConnection( url );
    }
}