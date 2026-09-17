class Resource {
    private final String resourceName;
    private boolean available = true;

    public Resource(String resourceName) {
        this.resourceName = resourceName;
    }

    public synchronized void acquire(String taskName)throws InterruptedException 
    {
        while (!available) {
            System.out.println(taskName + " is waiting for " + resourceName);
            wait();
        }
        available = false;
        System.out.println(taskName + " acquired " + resourceName);
    }

    public synchronized void release(String taskName) 
    {
        available = true;
        System.out.println(taskName +" released " + resourceName);
        notifyAll();
    }
}
