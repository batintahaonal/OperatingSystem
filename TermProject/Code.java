package ders1;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

// Enum for vehicle types
enum VehicleType {
    CAR(1, "Car"),
    MINIBUS(2, "Minibus"),
    TRUCK(3, "Truck");

    final int capacityUnits;
    final String name;

    VehicleType(int capacityUnits, String name) {
        this.capacityUnits = capacityUnits;
        this.name = name;
    }
}

// Represents a Toll Booth
class Toll {
    final int id; // Globally unique toll ID
    private final int side; // 0 or 1
    private final ReentrantLock lock = new ReentrantLock(); // Only one vehicle at a time

    public Toll(int id, int side) {
        this.id = id;
        this.side = side;
    }

    public void pass(Vehicle vehicle) {
        lock.lock();
        try {
            System.out.printf("%s %d is passing Toll %d (Side %d).%n",
                    vehicle.type.name, vehicle.id, this.id, this.side);
            Thread.sleep(100 + Simulation.RANDOM.nextInt(100)); // Simulate time to pass toll
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.printf("%s %d interrupted while passing Toll %d.%n", vehicle.type.name, vehicle.id, this.id);
        } finally {
            lock.unlock();
        }
    }
}

// Represents a Port (Side of the city)
class Port {
    final int sideId;
    final Toll[] tolls;
    // Waiting square: vehicles that passed tolls and wait for the ferry
    final BlockingQueue<Vehicle> waitingSquare;

    public Port(int sideId, int numTollsOnThisSide) {
        this.sideId = sideId;
        this.tolls = new Toll[numTollsOnThisSide];
        for (int i = 0; i < numTollsOnThisSide; i++) {
            // Global toll IDs: side 0 has tolls 0,1; side 1 has tolls 2,3 (if numTollsPerSide = 2)
            this.tolls[i] = new Toll(sideId * Simulation.TOLLS_PER_SIDE + i, sideId);
        }
        // Capacity of waiting square can be total vehicles
        this.waitingSquare = new ArrayBlockingQueue<>(Simulation.TOTAL_VEHICLES);
    }

    public Toll getRandomToll() {
        return tolls[Simulation.RANDOM.nextInt(tolls.length)];
    }

    public void addToWaitingSquare(Vehicle vehicle) throws InterruptedException {
        System.out.printf("%s %d entered waiting square on Side %d.%n",
                vehicle.type.name, vehicle.id, sideId);
        waitingSquare.put(vehicle); // Blocks if full, but capacity is total vehicles
    }

    // Used by Ferry to get next vehicle
    public Vehicle pollFromWaitingSquare(long timeout, TimeUnit unit) throws InterruptedException {
        return waitingSquare.poll(timeout, unit);
    }
    
    // Used by Ferry to put a vehicle back if it can't board
    public void returnToWaitingSquare(Vehicle vehicle) throws InterruptedException {
        // Attempt to add to front, but ArrayBlockingQueue doesn't support it directly.
        // For simplicity, just add to the end. Or use a Deque.
        // Or, recreate the queue or manage a temporary list.
        // For now, just add it back. It might go to the end of the line.
        System.out.printf("%s %d being returned to waiting square on Side %d (was not boarded).%n",
                vehicle.type.name, vehicle.id, sideId);
        waitingSquare.put(vehicle);
    }
}


// Represents a Vehicle
class Vehicle implements Runnable {
    final int id;
    final VehicleType type;
    final int capacityUnits;
    final int startingSide;
    int currentSide;
    int targetSide;
    volatile boolean onFerry = false; // volatile as it's set by Ferry, read by Vehicle
    volatile boolean roundTripCompleted = false; // To help threads terminate cleanly

    final Ferry ferry;
    final Port[] ports;
    final CountDownLatch roundTripLatch; // To signal completion of its own round trip

    private static final AtomicInteger idCounter = new AtomicInteger(0);

    public Vehicle(VehicleType type, Ferry ferry, Port[] ports, CountDownLatch roundTripLatch) {
        this.id = idCounter.incrementAndGet();
        this.type = type;
        this.capacityUnits = type.capacityUnits;
        this.ferry = ferry;
        this.ports = ports;
        this.roundTripLatch = roundTripLatch;

        this.startingSide = Simulation.RANDOM.nextInt(2); // 0 or 1
        this.currentSide = this.startingSide;
        this.targetSide = 1 - this.startingSide; // Opposite side

        System.out.printf("%s %d created. Starts at Side %d, wants to go to Side %d first.%n",
                this.type.name, this.id, this.currentSide, this.targetSide);
    }

    @Override
    public void run() {
        try {
            // First leg of the trip
            System.out.printf("%s %d starting first leg: Side %d -> Side %d.%n", type.name, id, currentSide, targetSide);
            makeTrip();
            if (Thread.currentThread().isInterrupted()) {
				return;
			}


            // Prepare for return trip
            this.targetSide = this.startingSide; // Target is now where it started
            System.out.printf("%s %d arrived at Side %d. Preparing return to Side %d.%n",
                    type.name, id, currentSide, targetSide);
            Thread.sleep(200 + Simulation.RANDOM.nextInt(300)); // "Wait a while"

            // Second leg (return trip)
            System.out.printf("%s %d starting return leg: Side %d -> Side %d.%n", type.name, id, currentSide, targetSide);
            makeTrip();
            if (Thread.currentThread().isInterrupted()) {
				return;
			}


            roundTripCompleted = true;
            System.out.printf("%s %d COMPLETED ROUND TRIP, back at starting Side %d.%n",
                    type.name, id, currentSide);

        } catch (InterruptedException e) {
            System.out.printf("%s %d was INTERRUPTED during its journey.%n", type.name, id);
            Thread.currentThread().interrupt(); // Preserve interrupt status
        } finally {
            // Ensure latch is counted down even if interrupted after one leg,
            // though ideally interruption means simulation shutdown.
            // For this problem, we assume vehicles try to complete full round trip.
             if (roundTripCompleted) { // Only count down if fully completed
                roundTripLatch.countDown();
            }
        }
    }

    private void makeTrip() throws InterruptedException {
        if (roundTripCompleted || Thread.currentThread().isInterrupted()) {
			return;
		}

        // 1. Go to toll on currentSide
        Port currentPortObject = ports[currentSide];
        Toll toll = currentPortObject.getRandomToll();
        // System.out.printf("%s %d (Side %d) heading to Toll %d.%n", type.name, id, currentSide, toll.id);
        toll.pass(this);
        if (Thread.currentThread().isInterrupted()) {
			return;
		}


        // 2. Enter waiting square
        currentPortObject.addToWaitingSquare(this);
        ferry.signalVehicleWaiting(); // Important: tell ferry someone is in a square

        // 3. Wait to be picked for boarding by the ferry
        // This vehicle thread now waits until the ferry actually boards it.
        // The ferry's logic will pull from the queue.
        synchronized (this) { // Vehicle waits on its own monitor
            while (!onFerry && !roundTripCompleted && !Thread.currentThread().isInterrupted()) {
                // System.out.printf("%s %d (Side %d) is in square, waiting to be chosen by ferry.%n", type.name, id, currentSide);
                this.wait(1000); // Wait for ferry to call confirmBoarded() or timeout
            }
        }
        if (!onFerry || roundTripCompleted || Thread.currentThread().isInterrupted()) { // If not boarded (e.g. simulation ended) or already done
             if(!onFerry && !roundTripCompleted) {
				System.out.printf("%s %d (Side %d) timed out or was skipped for boarding, will retry or end.%n", type.name, id, currentSide);
			}
             // If not boarded, it means the simulation might be ending or an issue occurred.
             // For simplicity, we assume it will eventually be boarded if simulation is running.
             // If not boarded here, something is wrong or simulation is ending.
             if (!onFerry) {
				return; // Critical: if not boarded, cannot proceed with trip
			}
        }
        System.out.printf("%s %d CONFIRMED BOARDED on ferry at Side %d.%n", type.name, id, currentSide);


        // 4. On ferry, wait for arrival at targetSide AND then to be disembarked
        // The vehicle is now on the ferry. It waits until onFerry becomes false.
        synchronized (this) { // Vehicle waits on its own monitor
            while (onFerry && !roundTripCompleted && !Thread.currentThread().isInterrupted()) {
                // System.out.printf("%s %d is on ferry (at Side %d, going to %d), waiting for disembarkation signal.%n", type.name, id, ferry.getCurrentSideUnsafe(), targetSide);
                this.wait(1000); // Wait for ferry to call confirmDisembarked() or timeout
            }
        }
        if (onFerry || roundTripCompleted || Thread.currentThread().isInterrupted()) { // If still on ferry (e.g. simulation ended)
             if(onFerry && !roundTripCompleted) {
				System.out.printf("%s %d (Side %d) timed out or stuck on ferry, will retry or end.%n", type.name, id, currentSide);
			}
             if(onFerry) {
				return; // Critical: if still on ferry, cannot complete trip leg
			}
        }
        
        // 5. Arrived and disembarked
        this.currentSide = this.targetSide; // Update current side
        // Message for disembarkation is printed by the ferry.
        // System.out.printf("%s %d successfully disembarked at Side %d.%n", type.name, id, currentSide);
    }

    // Called by Ferry when this vehicle is chosen and boarded
    public void confirmBoarded() {
        synchronized (this) {
            this.onFerry = true;
            this.notifyAll(); // Notify this vehicle thread that it has been boarded
        }
    }

    // Called by Ferry when this vehicle is disembarked
    public void confirmDisembarked() {
        synchronized (this) {
            this.onFerry = false;
            this.notifyAll(); // Notify this vehicle thread that it has been disembarked
        }
    }
}


// Represents the Ferry
class Ferry implements Runnable {
    static final int MAX_CAPACITY = 20;
    private int currentLoad = 0;
    private int currentFerrySide; // 0 or 1
    private final List<Vehicle> vehiclesOnBoard = new ArrayList<>();
    private final Port[] ports;
    private volatile boolean unloadingInProgress = false;
    private final CountDownLatch allVehiclesDoneLatch;

    public Ferry(Port[] ports, CountDownLatch allVehiclesDoneLatch) {
        this.ports = ports;
        this.currentFerrySide = Simulation.RANDOM.nextInt(2);
        this.allVehiclesDoneLatch = allVehiclesDoneLatch;
        System.out.printf("Ferry starting at Side %d.%n", currentFerrySide);
    }
    
    // This method is for Vehicle to query, so it needs to be synchronized
    public synchronized int getCurrentSide() {
        return currentFerrySide;
    }
    
    // Unsafe version for logging from vehicle without acquiring ferry lock
    public int getCurrentSideUnsafe() { return currentFerrySide; }


    public synchronized int getCurrentLoad() {
        return currentLoad;
    }

    public synchronized boolean isUnloading() {
        return unloadingInProgress;
    }

    public synchronized boolean canBoard(Vehicle vehicle) {
        return currentLoad + vehicle.capacityUnits <= MAX_CAPACITY;
    }

    // Called by Vehicle when it enters a waiting square
    public synchronized void signalVehicleWaiting() {
        this.notifyAll(); // Wake up ferry if it was idle or waiting for vehicles
    }

    @Override
    public void run() {
        try {
            // Main loop: continues as long as not all vehicles have completed their round trips
            while (allVehiclesDoneLatch.getCount() > 0) {
                if (Thread.currentThread().isInterrupted()) {
					throw new InterruptedException();
				}

                boolean decidedToTravel = false;

                synchronized (this) {
                    // 1. UNLOAD vehicles if any are on board
                    if (!vehiclesOnBoard.isEmpty()) {
                        unloadingInProgress = true;
                        System.out.printf("Ferry at Side %d. UNLOADING %d vehicles.%n", currentFerrySide, vehiclesOnBoard.size());
                        notifyAll(); // Wake up vehicles on board waiting to disembark & vehicles on shore waiting for unloading to finish

                        List<Vehicle> tempVehiclesToUnload = new ArrayList<>(vehiclesOnBoard);
                        for (Vehicle v : tempVehiclesToUnload) {
                            disembark(v); // This calls v.confirmDisembarked() which notifies the vehicle
                            if (Thread.currentThread().isInterrupted()) {
								throw new InterruptedException();
							}
                            Thread.sleep(50); // Simulate time to disembark one vehicle
                        }
                        vehiclesOnBoard.clear(); // All unloaded
                        unloadingInProgress = false;
                        System.out.printf("Ferry at Side %d FINISHED UNLOADING. Load: %d/%d.%n", currentFerrySide, currentLoad, MAX_CAPACITY);
                        notifyAll(); // Signal that unloading is complete
                    }

                    // 2. LOAD vehicles
                    System.out.printf("Ferry at Side %d. Current load: %d. Checking waiting square for LOADING.%n", currentFerrySide, currentLoad);
                    Port currentPortObject = ports[currentFerrySide];
                    while (currentLoad < MAX_CAPACITY && allVehiclesDoneLatch.getCount() > 0) {
                        if (Thread.currentThread().isInterrupted()) {
							throw new InterruptedException();
						}

                        // Try to get a vehicle from the current port's waiting square
                        Vehicle vehicleToBoard = currentPortObject.pollFromWaitingSquare(50, TimeUnit.MILLISECONDS);

                        if (vehicleToBoard == null) { // No vehicle in square or short timeout expired
                            // If ferry is empty AND square is empty, it might wait a bit longer or decide to travel empty.
                            // If it has some load, or square is empty, it's done loading for now.
                            break; // Stop trying to load from this port for this stop
                        }

                        if (canBoard(vehicleToBoard)) {
                            board(vehicleToBoard); // This calls v.confirmBoarded()
                            Thread.sleep(50); // Simulate time to board one vehicle
                        } else {
                            // Cannot board this vehicle (not enough capacity). Put it back.
                            System.out.printf("Ferry at Side %d: Cannot board %s %d (needs %d, load %d/%d). Putting back.%n",
                                    currentFerrySide, vehicleToBoard.type.name, vehicleToBoard.id,
                                    vehicleToBoard.capacityUnits, currentLoad, MAX_CAPACITY);
                            currentPortObject.returnToWaitingSquare(vehicleToBoard); // Put it back
                            break; // Stop loading for this trip, as the head of the queue doesn't fit.
                        }
                        notifyAll(); // Notify vehicles about state change (load changed, one vehicle boarded)
                    } // End loading loop

                    // 3. DECIDE TO TRAVEL
                    if (currentLoad > 0) {
                        // Has vehicles, so travel
                        System.out.printf("Ferry departing Side %d with %d vehicles (load %d/%d) for Side %d.%n",
                                currentFerrySide, vehiclesOnBoard.size(), currentLoad, MAX_CAPACITY, 1 - currentFerrySide);
                        decidedToTravel = true;
                    } else { // Ferry is empty
                        if (allVehiclesDoneLatch.getCount() > 0) {
                            // Empty, but simulation not over. Must go to other side to check for vehicles.
                            System.out.printf("Ferry at Side %d is EMPTY. Square is empty. Traveling to check other side (Side %d).%n",
                                    currentFerrySide, 1 - currentFerrySide);
                            decidedToTravel = true;
                        } else {
                            // Empty AND all vehicles are done. Ferry's job is finished.
                            System.out.println("Ferry is empty and all vehicles completed trips. Service ending.");
                            // Loop condition `allVehiclesDoneLatch.getCount() > 0` will handle termination.
                        }
                    }
                    if (decidedToTravel) {
						notifyAll(); // Notify vehicles on board about impending departure
					}
                } // synchronized(this) block ends for decision making

                // 4. PERFORM TRAVEL (if decided) - outside synchronized block to allow other operations
                if (decidedToTravel) {
                    Thread.sleep(500 + Simulation.RANDOM.nextInt(500)); // Simulate travel time

                    synchronized (this) { // Re-acquire lock to update side and notify
                        currentFerrySide = 1 - currentFerrySide; // Arrive at the other side
                        System.out.printf("Ferry ARRIVED at Side %d.%n", currentFerrySide);
                        notifyAll(); // Notify all waiting threads about arrival and side change
                    }
                } else if (allVehiclesDoneLatch.getCount() == 0) {
                    // This means currentLoad was 0, AND all vehicles were done.
                    break; // Exit the main while loop of the ferry
                } else {
                    // This case implies: currentLoad is 0, decidedToTravel is false, but not all vehicles are done.
                    // This should not happen if the logic above for "decidedToTravel = true" when empty and not done is correct.
                    // However, as a fallback, wait a bit and re-evaluate.
                    synchronized(this) {
                         System.out.printf("Ferry at Side %d is idle (empty, no one to pick up here). Waiting briefly before re-evaluating.%n", currentFerrySide);
                         wait(200); // Wait briefly for any new vehicles to arrive in square
                    }
                }
            } // end while (allVehiclesDoneLatch.getCount() > 0)
        } catch (InterruptedException e) {
            System.out.println("Ferry thread was INTERRUPTED. Shutting down ferry service.");
            Thread.currentThread().interrupt();
        } finally {
            System.out.println("Ferry service finished.");
        }
    }

    // These are helper methods, always called within a synchronized(this) block in run()
    private void board(Vehicle vehicle) {
        vehiclesOnBoard.add(vehicle);
        currentLoad += vehicle.capacityUnits;
        System.out.printf("%s %d (costs %d) BOARDED ferry at Side %d. Ferry load: %d/%d.%n",
                vehicle.type.name, vehicle.id, vehicle.capacityUnits, currentFerrySide, currentLoad, MAX_CAPACITY);
        vehicle.confirmBoarded(); // Signal the specific vehicle
    }

    private void disembark(Vehicle vehicle) {
        // vehiclesOnBoard.remove(vehicle); // Done via clear() or iterating a temp list
        currentLoad -= vehicle.capacityUnits;
        System.out.printf("%s %d (costs %d) DISEMBARKED ferry at Side %d. Ferry load: %d/%d.%n",
                vehicle.type.name, vehicle.id, vehicle.capacityUnits, currentFerrySide, currentLoad, MAX_CAPACITY);
        vehicle.confirmDisembarked(); // Signal the specific vehicle
    }
}


public class Simulation {
    public static final Random RANDOM = new Random();
    public static final int NUM_CARS = 12;
    public static final int NUM_MINIBUSES = 10;
    public static final int NUM_TRUCKS = 8;
    public static final int TOTAL_VEHICLES = NUM_CARS + NUM_MINIBUSES + NUM_TRUCKS;
    public static final int TOLLS_PER_SIDE = 2;

    public static void main(String[] args) {
        System.out.println("Ferry Simulation Starting...");
        System.out.printf("Config: Cars=%d, Minibuses=%d, Trucks=%d. Ferry Capacity=%d. Tolls/Side=%d%n",
                NUM_CARS, NUM_MINIBUSES, NUM_TRUCKS, Ferry.MAX_CAPACITY, TOLLS_PER_SIDE);

        Port[] ports = new Port[2];
        ports[0] = new Port(0, TOLLS_PER_SIDE); // Side 0
        ports[1] = new Port(1, TOLLS_PER_SIDE); // Side 1

        // Latch to wait for all vehicles to complete their round trips
        CountDownLatch allVehiclesDoneLatch = new CountDownLatch(TOTAL_VEHICLES);

        Ferry ferry = new Ferry(ports, allVehiclesDoneLatch);
        Thread ferryThread = new Thread(ferry, "Ferry-Thread");

        List<Thread> vehicleThreads = new ArrayList<>();
        // Create Car threads
        for (int i = 0; i < NUM_CARS; i++) {
            Vehicle car = new Vehicle(VehicleType.CAR, ferry, ports, allVehiclesDoneLatch);
            vehicleThreads.add(new Thread(car, car.type.name + "-" + car.id));
        }
        // Create Minibus threads
        for (int i = 0; i < NUM_MINIBUSES; i++) {
            Vehicle minibus = new Vehicle(VehicleType.MINIBUS, ferry, ports, allVehiclesDoneLatch);
            vehicleThreads.add(new Thread(minibus, minibus.type.name + "-" + minibus.id));
        }
        // Create Truck threads
        for (int i = 0; i < NUM_TRUCKS; i++) {
            Vehicle truck = new Vehicle(VehicleType.TRUCK, ferry, ports, allVehiclesDoneLatch);
            vehicleThreads.add(new Thread(truck, truck.type.name + "-" + truck.id));
        }

        System.out.println("\nStarting ferry thread...");
        ferryThread.start();

        System.out.printf("Starting %d vehicle threads...%n%n", vehicleThreads.size());
        for (Thread vt : vehicleThreads) {
            vt.start();
            try { Thread.sleep(20); } catch (InterruptedException e) {} // Stagger starts slightly
        }

        System.out.println("\nAll threads started. Main thread waiting for all vehicles to complete round trips...");
        try {
            allVehiclesDoneLatch.await(); // Wait for all vehicles to finish
            System.out.println("\nALL VEHICLES HAVE COMPLETED THEIR ROUND TRIPS.");

            System.out.println("Signaling ferry thread to terminate (if not already done due to latch)...");
            // Ferry thread should terminate on its own by checking the latch.
            // We can interrupt it as a secondary measure if it's stuck in a sleep/wait.
            ferryThread.interrupt(); // Politely ask it to stop if it's waiting
            ferryThread.join(5000); // Wait for ferry thread to die

            if (ferryThread.isAlive()) {
                System.out.println("Ferry thread did not terminate gracefully. Forcing shutdown (not implemented here).");
            }

             // Vehicle threads should have terminated. We can join them too if needed.
            System.out.println("Waiting for vehicle threads to join (should be quick)...");
            for (Thread vt : vehicleThreads) {
                vt.join(1000); // Wait for each vehicle thread
                 if (vt.isAlive()) {
                    System.out.println(vt.getName() + " is still alive! Interrupting.");
                    vt.interrupt();
                    vt.join(500);
                }
            }

        } catch (InterruptedException e) {
            System.err.println("Main thread interrupted while waiting for simulation to end.");
            Thread.currentThread().interrupt();
        }

        System.out.println("\nFerry Simulation Ended.");
    }
}