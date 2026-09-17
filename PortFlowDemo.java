public class PortFlowDemo {

    public static void main(String[] args) {

        Resource conveyor = new Resource("Conveyor-C1");

        Thread task1 =new Thread(new CargoTask("IceCream-Task", conveyor));

        Thread task2 =new Thread(new CargoTask("NonVeg-Task", conveyor));

        task1.start();
        task2.start();
    }
}
