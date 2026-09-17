public class CargoTask implements Runnable {

    private final String taskName;
    private final Resource resource;

    public CargoTask(String taskName, Resource resource) {
        this.taskName = taskName;
        this.resource = resource;
    }

    @Override
    public void run() 
    {
        try 
        {
            resource.acquire(taskName);

            System.out.println(taskName + " is processing cargo...");

            Thread.sleep(3000);

            resource.release(taskName);

        } 
        catch (InterruptedException e) 
        {
            Thread.currentThread().interrupt();
        }
    }
}
