package examples;

import concurrent._, concurrent.ops._;

class ComputeServer(n: Int) {

  private trait Job {
    type t;
    def task: t;
    def ret(x: t): Unit;
  }

  private val openJobs = new Channel[Job]();

  private def processor(i: Int): Unit = {
    while (true) {
      val job = openJobs.read;
      Console.println("read a job");
      job.ret(job.task) 
    }
  }

  def future[a](def p: a): () => a = {
    val reply = new SyncVar[a]();
    openJobs.write{
      new Job { 
	type t = a;
	def task = p;
	def ret(x: a) = reply.set(x);
      }
    }
    () => reply.get
  }

  spawn(replicate(0, n) { processor })
}

object Test with Executable {
  val server = new ComputeServer(1);
  val f = server.future(42);
  Console.println(f())
}
