import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class SistemaEscalonador {

	// --- Configuration ---
	private static final int DELTA_T = 5; // Example: 5 instructions per time slice

	// ... (Memory, Word, Opcode remain the same) ...
	public class Memory {
		public Word[] pos;

		public Memory(int size) {
			pos = new Word[size];
			for (int i = 0; i < pos.length; i++) {
				pos[i] = new Word(Opcode.___, -1, -1, -1);
			}
		}

		public int getSize() {
			return pos.length;
		}
	}

	public class Word {
		public Opcode opc;
		public int ra;
		public int rb;
		public int p;

		public Word(Opcode _opc, int _ra, int _rb, int _p) {
			opc = _opc;
			ra = _ra;
			rb = _rb;
			p = _p;
		}
	}

	public enum Opcode {
		DATA, ___,
		JMP, JMPI, JMPIG, JMPIL, JMPIE,
		JMPIM, JMPIGM, JMPILM, JMPIEM,
		JMPIGK, JMPILK, JMPIEK, JMPIGT,
		ADDI, SUBI, ADD, SUB, MULT,
		LDI, LDD, STD, LDX, STX, MOVE,
		SYSCALL, STOP
	}

	public enum Interrupts {
		noInterrupt, intEnderecoInvalido, intInstrucaoInvalida, intOverflow,
		intSTOP, // STOP instruction treated as an interrupt source
		intTempo; // NEW: Timer interrupt for preemption
	}

	public class CPU {
		private int maxInt;
		private int minInt;

		private int pc; // Program Counter (Logical Address)
		private Word ir; // Instruction Register
		private int[] reg; // Registers R0-R9
		private Interrupts irpt; // Interrupt flag
		private int cycleCounter; // NEW: Counts executed instructions for time slice
		private int quantum; // NEW: Time slice duration (DELTA_T)

		private Word[] m; // Physical memory reference
		private List<Integer> page_table; // Current process's page table
		private int pageSize; // Size of a page/frame

		private InterruptHandling ih; // Reference to Interrupt Handler
		private SysCallHandling sysCall; // Reference to SysCall Handler
		private Utilities u; // Reference to Utilities

		private volatile boolean cpuStop; // MODIFICADO: volatile para visibilidade entre threads
		private boolean debug; // Trace flag

		public CPU(Memory _mem, boolean _debug, int _pageSize, int _quantum) {
			maxInt = 32767;
			minInt = -32767;
			m = _mem.pos;
			reg = new int[10];
			debug = _debug;
			pageSize = _pageSize;
			quantum = _quantum; // Set the quantum
			pc = 0;
			irpt = Interrupts.noInterrupt;
			cycleCounter = 0; // Initialize cycle counter
			cpuStop = true; // Start in stopped state
		}

		public void setAddressOfHandlers(InterruptHandling _ih, SysCallHandling _sysCall) {
			ih = _ih;
			sysCall = _sysCall;
		}

		public void setUtilities(Utilities _u) {
			u = _u;
		}

		private boolean legal(int e) {
			if (e >= 0 && e < m.length) {
				return true;
			} else {
				irpt = Interrupts.intEnderecoInvalido;
				System.err.println(">>> ERRO CPU: Endereco fisico invalido: " + e);
				return false;
			}
		}

		// Adicionar este método dentro da classe CPU
		/**
		 * Traduz um endereço lógico para físico usando uma tabela de páginas
		 * específica,
		 * sem depender do contexto atualmente carregado na CPU.
		 * 
		 * @param logicalAddress   O endereço lógico a ser traduzido.
		 * @param processPageTable A tabela de páginas do processo em questão.
		 * @return O endereço físico, ou -1 em caso de erro.
		 */
		public int translateAddress(int logicalAddress, List<Integer> processPageTable) {
			if (processPageTable == null) {
				System.err.println(">>> ERRO CPU (translate): Tabela de paginas fornecida eh nula!");
				return -1;
			}
			int pageNumber = logicalAddress / pageSize;
			int offset = logicalAddress % pageSize;

			if (pageNumber < 0 || pageNumber >= processPageTable.size() || processPageTable.get(pageNumber) == null) {
				System.err.println(">>> ERRO CPU (translate): Endereco logico " + logicalAddress + " (pagina "
						+ pageNumber + ") fora dos limites ou nao mapeado na tabela fornecida.");
				return -1;
			}

			int frameNumber = processPageTable.get(pageNumber);
			int physicalAddress = (frameNumber * pageSize) + offset;

			// Apenas uma verificação final de sanidade nos limites da memória física
			if (physicalAddress < 0 || physicalAddress >= m.length) {
				System.err.println(">>> ERRO CPU (translate): Endereco fisico calculado " + physicalAddress
						+ " esta fora dos limites da memoria.");
				return -1;
			}

			return physicalAddress;
		}

		private boolean testOverflow(int v) {
			if ((v < minInt) || (v > maxInt)) {
				irpt = Interrupts.intOverflow;
				System.err.println(">>> ERRO CPU: Overflow com valor: " + v);
				return false;
			}
			return true;
		}

		public void setContext(int _pc, List<Integer> _page_table, int[] _regs) {
			pc = _pc;
			page_table = _page_table;
			System.arraycopy(_regs, 0, this.reg, 0, _regs.length);
			irpt = Interrupts.noInterrupt;
			cycleCounter = 0; // Reset cycle counter for new context/slice
			cpuStop = false; // Ensure CPU is ready to run
			if (debug) {
				System.out.println("CPU: Contexto carregado - PC_log=" + pc + " Paginas=" + page_table);
			}
		}

		private int translateAddress(int logicalAddress) {
			if (page_table == null) {
				System.err.println(">>> ERRO CPU: Tentativa de traducao sem tabela de paginas carregada!");
				irpt = Interrupts.intEnderecoInvalido;
				return -1;
			}
			int pageNumber = logicalAddress / pageSize;
			int offset = logicalAddress % pageSize;

			if (pageNumber < 0 || pageNumber >= page_table.size() || page_table.get(pageNumber) == null) {
				System.err.println(">>> ERRO CPU: Endereco logico " + logicalAddress + " (pagina " + pageNumber
						+ ") fora dos limites da tabela ou pagina nao mapeada.");
				irpt = Interrupts.intEnderecoInvalido;
				return -1;
			}

			int frameNumber = page_table.get(pageNumber);
			int physicalAddress = (frameNumber * pageSize) + offset;
			return physicalAddress;
		}

		public int getPC() {
			return pc;
		}

		public int[] getRegs() {
			return Arrays.copyOf(reg, reg.length);
		}

		public void setDebug(boolean _debug) {
			this.debug = _debug;
			System.out.println("CPU: Modo trace " + (this.debug ? "ativado." : "desativado."));
		}

		public void stopCPU() {
			this.cpuStop = true;
		}

		public boolean isStopped() {
			return this.cpuStop;
		}

		public void run() {
			if (page_table == null) {
				System.err.println("CPU: Nao pode executar sem uma tabela de paginas carregada.");
				cpuStop = true;
				return;
			}

			if (cpuStop)
				return;

			// Main fetch-decode-execute cycle
			while (!cpuStop && irpt == Interrupts.noInterrupt) {

				// 1. Fetch Instruction
				int physicalPC = translateAddress(pc);
				if (irpt != Interrupts.noInterrupt)
					break;
				if (!legal(physicalPC))
					break;

				ir = m[physicalPC];

				if (debug) {
					System.out.print("  CPU Ciclo:" + cycleCounter + " PC_log=" + pc + " PC_fis=" + physicalPC + " IR=["
							+ ir.opc + "," + ir.ra + "," + ir.rb + "," + ir.p + "] ");
					System.out.print(" Regs=[");
					for (int i = 0; i < reg.length; i++) {
						System.out.print("r" + i + ":" + reg[i] + (i == reg.length - 1 ? "" : ","));
					}
					System.out.println("]");
				}

				// 2. Decode and Execute Instruction
				Opcode currentOpc = ir.opc;
				int originalPC = pc;

				switch (currentOpc) {
					case LDI:
						reg[ir.ra] = ir.p;
						pc++;
						break;
					case LDD: {
						int logicalAddress = ir.p;
						int physicalAddress = translateAddress(logicalAddress);
						if (irpt == Interrupts.noInterrupt && legal(physicalAddress)) {
							if (m[physicalAddress].opc == Opcode.DATA) {
								reg[ir.ra] = m[physicalAddress].p;
								pc++;
							} else {
								irpt = Interrupts.intInstrucaoInvalida;
							}
						}
						break;
					}
					case LDX: {
						int logicalAddress = reg[ir.rb];
						int physicalAddress = translateAddress(logicalAddress);
						if (irpt == Interrupts.noInterrupt && legal(physicalAddress)) {
							if (m[physicalAddress].opc == Opcode.DATA) {
								reg[ir.ra] = m[physicalAddress].p;
								pc++;
							} else {
								irpt = Interrupts.intInstrucaoInvalida;
							}
						}
						break;
					}
					case STD: {
						int logicalAddress = ir.p;
						int physicalAddress = translateAddress(logicalAddress);
						if (irpt == Interrupts.noInterrupt && legal(physicalAddress)) {
							m[physicalAddress].opc = Opcode.DATA;
							m[physicalAddress].p = reg[ir.ra];
							pc++;
						}
						break;
					}
					case STX: {
						int logicalAddress = reg[ir.ra];
						int physicalAddress = translateAddress(logicalAddress);
						if (irpt == Interrupts.noInterrupt && legal(physicalAddress)) {
							m[physicalAddress].opc = Opcode.DATA;
							m[physicalAddress].p = reg[ir.rb];
							pc++;
						}
						break;
					}
					case MOVE:
						reg[ir.ra] = reg[ir.rb];
						pc++;
						break;

					case ADD:
						reg[ir.ra] = reg[ir.ra] + reg[ir.rb];
						if (testOverflow(reg[ir.ra]))
							pc++;
						break;
					case ADDI:
						reg[ir.ra] = reg[ir.ra] + ir.p;
						if (testOverflow(reg[ir.ra]))
							pc++;
						break;
					case SUB:
						reg[ir.ra] = reg[ir.ra] - reg[ir.rb];
						if (testOverflow(reg[ir.ra]))
							pc++;
						break;
					case SUBI:
						reg[ir.ra] = reg[ir.ra] - ir.p;
						if (testOverflow(reg[ir.ra]))
							pc++;
						break;
					case MULT:
						reg[ir.ra] = reg[ir.ra] * reg[ir.rb];
						if (testOverflow(reg[ir.ra]))
							pc++;
						break;

					case JMP:
						pc = ir.p;
						break;
					case JMPI:
						pc = reg[ir.ra];
						break;
					case JMPIG:
						if (reg[ir.rb] > 0)
							pc = reg[ir.ra];
						else
							pc++;
						break;
					case JMPIL:
						if (reg[ir.rb] < 0)
							pc = reg[ir.ra];
						else
							pc++;
						break;
					case JMPIE:
						if (reg[ir.rb] == 0)
							pc = reg[ir.ra];
						else
							pc++;
						break;
					case JMPIM: {
						int logicalAddress = ir.p;
						int physicalAddress = translateAddress(logicalAddress);
						if (irpt == Interrupts.noInterrupt && legal(physicalAddress)) {
							pc = m[physicalAddress].p;
						}
						break;
					}
					case JMPIGM: {
						if (reg[ir.rb] > 0) {
							int logicalAddress = ir.p;
							int physicalAddress = translateAddress(logicalAddress);
							if (irpt == Interrupts.noInterrupt && legal(physicalAddress)) {
								pc = m[physicalAddress].p;
							}
						} else {
							pc++;
						}
						break;
					}
					case JMPILM: {
						if (reg[ir.rb] < 0) {
							int logicalAddress = ir.p;
							int physicalAddress = translateAddress(logicalAddress);
							if (irpt == Interrupts.noInterrupt && legal(physicalAddress)) {
								pc = m[physicalAddress].p;
							}
						} else {
							pc++;
						}
						break;
					}
					case JMPIEM: {
						if (reg[ir.rb] == 0) {
							int logicalAddress = ir.p;
							int physicalAddress = translateAddress(logicalAddress);
							if (irpt == Interrupts.noInterrupt && legal(physicalAddress)) {
								pc = m[physicalAddress].p;
							}
						} else {
							pc++;
						}
						break;
					}
					case JMPIGK:
						if (reg[ir.rb] > 0)
							pc = ir.p;
						else
							pc++;
						break;
					case JMPILK:
						if (reg[ir.rb] < 0)
							pc = ir.p;
						else
							pc++;
						break;
					case JMPIEK:
						if (reg[ir.rb] == 0)
							pc = ir.p;
						else
							pc++;
						break;
					case JMPIGT:
						if (reg[ir.ra] > reg[ir.rb])
							pc = ir.p;
						else
							pc++;
						break;

					case SYSCALL:
						pc++; // AVANÇA O PC ANTES DE EXECUTAR O HANDLER
						ProcessControlBlock pcb = so.gp.getRunningProcess();
						sysCall.handle(pcb);
						break;

					case STOP:
						irpt = Interrupts.intSTOP;
						break;

					case DATA:
					case ___:
					default:
						irpt = Interrupts.intInstrucaoInvalida;
						System.err.println(
								">>> ERRO CPU: Opcode invalido (" + currentOpc + ") em PC logico " + originalPC);
						break;
				}

				if (irpt == Interrupts.noInterrupt) {
					cycleCounter++;
					if (cycleCounter >= quantum) {
						irpt = Interrupts.intTempo;
						if (debug)
							System.out.println("CPU: Quantum (" + quantum + ") atingido. Gerando intTempo.");
					}
				}
			}

			// 4. Handle Pending Interrupt
			if (irpt != Interrupts.noInterrupt) {
				ih.handle(irpt, pc);
			} else if (cpuStop) {
				if (debug)
					System.out.println("CPU: Execucao parada externamente.");
			}

			// cpuStop = true;
		}
	}

	public class InterruptHandling {
		private CPU cpu;
		private SO so;

		public InterruptHandling(SO _so) {
			this.so = _so;
			this.cpu = _so.hw.cpu;
		}

		public void handle(Interrupts irpt, int interruptedPC) {
			ProcessControlBlock currentProcess = so.gp.getRunningProcess();

			if (currentProcess == null) {
				System.err.println("IH: Erro CRITICO - Interrupção " + irpt + " sem processo rodando!");
				cpu.stopCPU();
				return;
			}

			System.out.println("-----------------------------------------------------");
			System.out.println(">>> INTERRUPCAO: " + irpt + " ocorrida em PC logico: " + interruptedPC
					+ " (Processo PID: " + currentProcess.pid + ")");

			switch (irpt) {
				case intTempo:
					// Preempção
					so.gp.saveContext(currentProcess);
					so.gp.addToReadyQueue(currentProcess);
					so.gp.schedule();
					break;

				case intSTOP:
					// Término do processo
					so.gp.terminateProcess(currentProcess);
					so.gp.schedule();
					break;

				case intEnderecoInvalido:
				case intInstrucaoInvalida:
				case intOverflow:
					// Erro irrecuperável
					System.err
							.println("IH: Erro irrecuperavel no processo PID " + currentProcess.pid + ". Terminando.");
					so.gp.terminateProcess(currentProcess);
					so.gp.schedule();
					break;

				case noInterrupt:
				default:
					System.err.println("IH: Handler chamado com interrupcao inesperada: " + irpt);
					break;
			}
			System.out.println("-----------------------------------------------------");
		}
	}

	public class SysCallHandling {
		private CPU cpu;
		private SO so;

		public SysCallHandling(SO _so) {
			this.so = _so;
			this.cpu = _so.hw.cpu;
		}

		// MODIFICADO: handle agora recebe o PCB para bloquear
		public void handle(ProcessControlBlock pcb) {
			int operation = cpu.reg[8];
			int arg = cpu.reg[9];

			System.out.println(">>> SYSCALL: Processo PID " + pcb.pid + " Operacao=" + operation);

			switch (operation) {
				case 1: // IN
					System.out.println(" (Requisicao de INPUT para endereco logico " + arg + ")");
					so.gp.blockProcessForIO(pcb, arg, true); // Bloqueia e agenda IO
					break;

				case 2: // OUT
					System.out.println(" (Requisicao de OUTPUT para endereco logico " + arg + ")");
					so.gp.blockProcessForIO(pcb, arg, false); // Bloqueia e agenda IO
					break;

				default:
					System.out.println(" (Codigo de Operacao Invalido: " + operation + ")");
					cpu.irpt = Interrupts.intInstrucaoInvalida;
					break;
			}
		}
	}

	public class Utilities {
		private HW hw;

		public Utilities(HW _hw) {
			hw = _hw;
		}

		public void dump(Word w) {
			System.out.printf("[ %-7s %3d %3d %4d ]", w.opc, w.ra, w.rb, w.p);
		}

		public void dump(int ini, int fim) {
			System.out.println("--- Dump da Memoria Fisica (Enderecos: " + ini + " a " + (fim - 1) + ") ---");
			Word[] m = hw.mem.pos;
			int end = Math.min(fim, m.length);
			int start = Math.max(0, ini);

			for (int i = start; i < end; i++) {
				System.out.printf("%04d: ", i);
				dump(m[i]);
				System.out.println();
			}
			System.out.println("--------------------------------------------------");
		}

		public boolean loadProgramToMemory(Word[] program, List<Integer> pageTable) {
			int programSize = program.length;
			int pageSize = hw.pageSize;
			Word[] memory = hw.mem.pos;

			for (int i = 0; i < programSize; i++) {
				int logicalAddress = i;
				int pageNumber = logicalAddress / pageSize;
				int offset = logicalAddress % pageSize;

				if (pageNumber >= pageTable.size() || pageTable.get(pageNumber) == null) {
					System.err.println("UTILS: Erro ao carregar - Tabela de paginas nao tem entrada para pagina logica "
							+ pageNumber);
					return false;
				}

				int frameNumber = pageTable.get(pageNumber);
				int physicalAddress = (frameNumber * pageSize) + offset;

				if (physicalAddress >= 0 && physicalAddress < memory.length) {
					memory[physicalAddress] = program[i];
				} else {
					System.err.println(
							"UTILS: Erro ao carregar - Endereco fisico calculado invalido: " + physicalAddress);
					return false;
				}
			}
			return true;
		}
	}

	public class Contexto {
		public int[] regs;
		public int pc;

		public Contexto() {
			this.pc = 0;
			this.regs = new int[10];
			Arrays.fill(this.regs, 0);
		}
	}

	// MODIFICADO: Adicionado Enum para estados do processo
	public enum ProcessState {
		READY,
		RUNNING,
		BLOCKED,
		TERMINATED
	}

	public class ProcessControlBlock {
		public int pid;
		public List<Integer> pageTable;
		public Contexto contexto;
		public String programName;
		public ProcessState state; // MODIFICADO: Adicionado estado ao PCB

		public ProcessControlBlock(int pid, List<Integer> pageTable, String programName) {
			this.pid = pid;
			this.pageTable = pageTable;
			this.programName = programName;
			this.contexto = new Contexto();
			this.state = ProcessState.READY; // MODIFICADO: Estado inicial é READY
		}
	}

	public class ProcessManagement {

		private LinkedList<ProcessControlBlock> aptos;
		private LinkedList<ProcessControlBlock> bloqueados;
		private volatile ProcessControlBlock running; // MODIFICADO: volatile para visibilidade
		private CPU cpu;
		private MemoryManagment mm;
		private Utilities utils;

		private AtomicInteger nextPid = new AtomicInteger(0);
		private Lock schedulerLock = new ReentrantLock(); // MODIFICADO: Lock para proteger o escalonamento

		public ProcessManagement(CPU _cpu, MemoryManagment _mm, Utilities _utils) {
			this.aptos = new LinkedList<>();
			this.bloqueados = new LinkedList<>(); // Fila de processos bloqueados
			this.running = null;
			this.cpu = _cpu;
			this.mm = _mm;
			this.utils = _utils;
		}

		// Adicionar este método dentro da classe ProcessManagement
		public ProcessControlBlock findProcessInBlockedQueue(int pid) {
			schedulerLock.lock();
			try {
				for (ProcessControlBlock pcb : bloqueados) {
					if (pcb.pid == pid) {
						return pcb;
					}
				}
				return null; // Não encontrado
			} finally {
				schedulerLock.unlock();
			}
		}

		public ProcessControlBlock getRunningProcess() {
			return running;
		}

		public boolean criaProcesso(Word[] programa, String programName) {
			schedulerLock.lock();
			try {
				if (programa == null || programa.length == 0) {
					System.out.println("GP: Erro - Programa invalido ou vazio.");
					return false;
				}
				int programSize = programa.length;
				System.out.println(
						"GP: Tentando criar processo para '" + programName + "' (" + programSize + " palavras).");

				List<Integer> pageTable = new ArrayList<>();
				if (!mm.aloca(programSize, pageTable)) {
					System.out.println("GP: Falha ao criar processo - Memoria insuficiente.");
					return false;
				}

				int pid = nextPid.getAndIncrement();
				ProcessControlBlock newPCB = new ProcessControlBlock(pid, pageTable, programName);

				if (!utils.loadProgramToMemory(programa, pageTable)) {
					System.err.println(
							"GP: Falha ao carregar o programa na memoria para PID " + pid + ". Desalocando memoria.");
					mm.desaloca(pageTable);
					return false;
				}

				addToReadyQueue(newPCB);
				System.out.println(
						"GP: Processo '" + programName + "' (PID " + pid + ") criado e adicionado a fila de aptos.");

				// Se a CPU estiver ociosa, dispara o escalonador
				if (running == null) {
					schedule();
				}
				return true;
			} finally {
				schedulerLock.unlock();
			}
		}

		public void addToReadyQueue(ProcessControlBlock pcb) {
			if (pcb != null) {
				pcb.state = ProcessState.READY;
				aptos.addLast(pcb);
				if (cpu.debug)
					System.out.println("GP: Processo PID " + pcb.pid + " adicionado/retornado a fila de aptos.");
			}
		}

		public void saveContext(ProcessControlBlock pcb) {
			if (pcb != null) {
				pcb.contexto.pc = cpu.getPC();
				pcb.contexto.regs = cpu.getRegs();
				if (cpu.debug) {
					System.out.println("GP: Contexto salvo para PID " + pcb.pid + " (PC=" + pcb.contexto.pc + ")");
				}
			}
		}

		public void terminateProcess(ProcessControlBlock pcb) {
			if (pcb != null) {
				pcb.state = ProcessState.TERMINATED;
				System.out.println("GP: Terminando processo PID: " + pcb.pid + " ('" + pcb.programName + "')");
				mm.desaloca(pcb.pageTable);
				if (running == pcb) {
					running = null;
				}
				System.out.println("GP: Recursos desalocados para PID " + pcb.pid);
			}
		}

		// MODIFICADO: Bloqueia o processo e agenda o IO
		public void blockProcessForIO(ProcessControlBlock pcb, int address, boolean isRead) {
			schedulerLock.lock();
			try {
				saveContext(pcb);
				pcb.state = ProcessState.BLOCKED;
				if (running == pcb)
					running = null; // Libera a CPU
				bloqueados.add(pcb);

				// Adiciona pedido na fila de IO
				so.filaIO.add(new PedidoIO(pcb.pid, address, isRead));

				System.out.println("GP: Processo PID " + pcb.pid + " bloqueado para IO.");

				// Escalone o próximo
				schedule();
			} finally {
				schedulerLock.unlock();
			}
		}

		// MODIFICADO: Chamado pela thread de interrupção de IO
		public void unblockProcess(int pid) {
			schedulerLock.lock();
			try {
				ProcessControlBlock pcbToUnblock = null;
				Iterator<ProcessControlBlock> it = bloqueados.iterator();
				while (it.hasNext()) {
					ProcessControlBlock pcb = it.next();
					if (pcb.pid == pid) {
						pcbToUnblock = pcb;
						it.remove();
						break;
					}
				}

				if (pcbToUnblock != null) {
					System.out.println("GP: Desbloqueando processo PID " + pcbToUnblock.pid + " (fim do IO).");
					addToReadyQueue(pcbToUnblock);
					// Se a CPU estava ociosa, pode ser necessário escalonar
					if (running == null) {
						schedule();
					}
				} else {
					System.err.println(
							"GP: Tentativa de desbloquear PID " + pid + " não encontrado na fila de bloqueados.");
				}
			} finally {
				schedulerLock.unlock();
			}
		}

		// O Escalonador
		public void schedule() {
			schedulerLock.lock();
			try {

				if (!aptos.isEmpty()) {
					running = aptos.removeFirst();
					running.state = ProcessState.RUNNING;

					System.out.println("GP: Escalonando proximo processo -> PID: " + running.pid + " ('"
							+ running.programName + "')");
					cpu.setContext(running.contexto.pc, running.pageTable, running.contexto.regs);

					// MODIFICADO: Notifica a thread da CPU para começar a execução
					so.cpuExecutionManager.signalCpuToRun();

				} else {
					running = null;
					System.out.println("GP: Fila de aptos VAZIA. Nenhum processo para escalonar. CPU ociosa.");
					cpu.stopCPU();
				}
			} finally {
				schedulerLock.unlock();
			}
		}

		public void listProcesses() {
			schedulerLock.lock();
			try {
				System.out.println("--- Lista de Processos Ativos ---");
				boolean found = false;
				if (running != null) {
					System.out.println("  PID: " + running.pid + "\t Nome: '" + running.programName
							+ "' \t Estado: " + running.state + " \t PC: " + cpu.getPC());
					found = true;
				}

				System.out.println("--- Fila de Aptos ---");
				if (!aptos.isEmpty()) {
					for (ProcessControlBlock pcb : aptos) {
						System.out.println("  PID: " + pcb.pid + "\t Nome: '" + pcb.programName
								+ "' \t Estado: " + pcb.state + " \t PC: " + pcb.contexto.pc);
					}
					found = true;
				} else {
					System.out.println("  (vazia)");
				}

				System.out.println("--- Fila de Bloqueados ---");
				if (!bloqueados.isEmpty()) {
					for (ProcessControlBlock pcb : bloqueados) {
						System.out.println("  PID: " + pcb.pid + "\t Nome: '" + pcb.programName
								+ "' \t Estado: " + pcb.state + " \t PC: " + pcb.contexto.pc);
					}
					found = true;
				} else {
					System.out.println("  (vazia)");
				}

				if (!found) {
					System.out.println("  Nenhum processo no sistema.");
				}
				System.out.println("---------------------------------");
			} finally {
				schedulerLock.unlock();
			}
		}
	}

	public class MemoryManagment {
		private Set<Integer> freeFrames;
		private int frameSize;
		private int totalFrames;

		public MemoryManagment(int tamMem, int tamFrame) {
			this.frameSize = tamFrame;
			this.totalFrames = tamMem / tamFrame;
			this.freeFrames = new HashSet<>();

			for (int i = 0; i < totalFrames; i++) {
				freeFrames.add(i);
			}
			System.out.println("GM: " + totalFrames + " frames livres de tamanho " + frameSize + " inicializados.");
		}

		public boolean aloca(int numPalavras, List<Integer> pageTable) {
			int numFramesNeeded = (int) Math.ceil((double) numPalavras / frameSize);
			if (freeFrames.size() < numFramesNeeded) {
				System.err.println("GM: Erro - Nao ha frames livres suficientes.");
				return false;
			}
			Iterator<Integer> iterator = freeFrames.iterator();
			for (int i = 0; i < numFramesNeeded; i++) {
				int frameNumber = iterator.next();
				pageTable.add(frameNumber);
				iterator.remove();
			}
			return true;
		}

		public void desaloca(List<Integer> pageTable) {
			if (pageTable == null || pageTable.isEmpty())
				return;
			System.out.println("GM: Desalocando frames: " + pageTable);
			freeFrames.addAll(pageTable);
			pageTable.clear();
			System.out.println("GM: Frames livres agora: " + freeFrames.size());
		}

		public int getFreeFrameCount() {
			return freeFrames.size();
		}

		public int getTotalFrames() {
			return totalFrames;
		}

		public int getFrameSize() {
			return frameSize;
		}

		public int getMemSize() {
			return totalFrames * frameSize;
		}
	}

	public class SO {
		public InterruptHandling ih;
		public SysCallHandling sc;
		public Utilities utils;
		public MemoryManagment mm;
		public ProcessManagement gp;
		public HW hw;
		public BlockingQueue<PedidoIO> filaIO;
		public BlockingQueue<Integer> filaInterrupcoesIO;
		public volatile boolean shutdown = false; // MODIFICADO: Flag para encerrar o sistema

		// MODIFICADO: Gerenciador da thread da CPU
		public CpuExecutionManager cpuExecutionManager;

		public SO(HW _hw) {
			this.hw = _hw;
			this.filaIO = new LinkedBlockingQueue<>();
			this.filaInterrupcoesIO = new LinkedBlockingQueue<>();

			mm = new MemoryManagment(hw.mem.getSize(), hw.pageSize);
			utils = new Utilities(hw);
			ih = new InterruptHandling(this);
			sc = new SysCallHandling(this);
			gp = new ProcessManagement(hw.cpu, mm, utils);

			hw.cpu.setAddressOfHandlers(ih, sc);
			hw.cpu.setUtilities(utils);

			// MODIFICADO: Inicializa o gerenciador da CPU
			this.cpuExecutionManager = new CpuExecutionManager(hw.cpu, this);

			System.out.println("SO: Sistema Operacional inicializado com Quantum = " + DELTA_T);
		}
	}

	public HW hw;
	public SO so;
	public Programs progs;

	public SistemaEscalonador(int tamMem, int page_size) {
		hw = new HW(tamMem, page_size, DELTA_T);
		so = new SO(hw);
		progs = new Programs();
		System.out.println("Sistema: Hardware e SO criados. Pronto para comandos.");
	}

	public class HW {
		public Memory mem;
		public CPU cpu;
		public int pageSize;

		public HW(int tamMem, int _pageSize, int _quantum) {
			mem = new Memory(tamMem);
			pageSize = _pageSize;
			cpu = new CPU(mem, true, pageSize, _quantum);
		}
	}

	// --- THREADS DO SISTEMA ---

	// MODIFICADO: Etapa 1 - Thread do Shell de Comandos
	public class ShellThread extends Thread {
		@Override
		public void run() {
			Scanner scanner = new Scanner(System.in);
			System.out.println("\n--- Simulador de SO v3.0 (Multithread) ---");
			System.out.println("Quantum (Delta T): " + DELTA_T + " instrucoes.");
			System.out.println("Digite 'help' para ver os comandos.");

			while (!so.shutdown) {
				System.out.print("\n> ");
				String line = scanner.nextLine().trim();
				if (line.isEmpty())
					continue;

				String[] parts = line.split("\\s+", 2);
				String command = parts[0].toLowerCase();
				String args = parts.length > 1 ? parts[1] : "";

				try {
					switch (command) {
						case "new":
							Word[] programImage = progs.retrieveProgram(args);
							if (programImage != null) {
								so.gp.criaProcesso(programImage, args);
							} else {
								System.out.println("Erro: Programa '" + args + "' nao encontrado.");
								System.out.println("Programas disponiveis: " + progs.getAvailableProgramNames());
							}
							break;

						case "ps":
							so.gp.listProcesses();
							break;

						case "traceon":
							hw.cpu.setDebug(true);
							break;

						case "traceoff":
							hw.cpu.setDebug(false);
							break;

						case "exit":
							System.out.println("Encerrando o simulador...");
							so.shutdown = true;
							so.cpuExecutionManager.shutdown(); // Avisa a thread da CPU para parar
							so.filaInterrupcoesIO.add(-1); // Sinal para a thread de IO parar
							so.filaIO.add(new PedidoIO(-1, -1, false)); // Sinal para a outra thread de IO parar
							scanner.close();
							return;

						case "help":
							System.out.println("Comandos disponiveis:");
							System.out.println("  new <nomePrograma>   - Cria um novo processo (Programas: "
									+ progs.getAvailableProgramNames() + ")");
							System.out.println("  ps                   - Lista processos (Running/Ready/Blocked)");
							System.out.println("  traceon / traceoff   - Ativa/Desativa modo de debug da CPU");
							System.out.println("  exit                 - Encerra o simulador");
							break;

						default:
							System.out.println("Comando desconhecido: '" + command + "'. Digite 'help' para ajuda.");
							break;
					}
				} catch (Exception e) {
					System.err.println("!!! Erro inesperado no Shell: " + e.getMessage());
					e.printStackTrace();
				}
			}
		}
	}

	// MODIFICADO: Etapa 1 e 4 - Thread que gerencia a execução da CPU
	public class CpuExecutionManager extends Thread {
		private final CPU cpu;
		private final SO so;
		private final Lock lock = new ReentrantLock();
		private final Condition canRun = lock.newCondition();
		private boolean shouldRun = false;

		public CpuExecutionManager(CPU cpu, SO so) {
			this.cpu = cpu;
			this.so = so;
		}

		@Override
		public void run() {
			while (!so.shutdown) {
				lock.lock();
				try {
					while (!shouldRun && !so.shutdown) {
						canRun.await(); // Espera o escalonador sinalizar
					}
					if (so.shutdown)
						break;

					shouldRun = false; // Reseta o sinal
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				} finally {
					lock.unlock();
				}

				// Executa a CPU se houver um processo
				if (so.gp.getRunningProcess() != null) {
					cpu.run(); // Executa a fatia de tempo
				}
			}
			System.out.println("Thread de Execução da CPU encerrada.");
		}

		public void signalCpuToRun() {
			lock.lock();
			try {
				shouldRun = true;
				canRun.signal(); // Acorda a thread da CPU
			} finally {
				lock.unlock();
			}
		}

		public void shutdown() {
			signalCpuToRun(); // Garante que a thread não está presa no await
		}
	}

	// MODIFICADO: Etapa 1 e 3 - Thread do Dispositivo de IO
	public class DispositivoIO extends Thread {
		private SO so;

		public DispositivoIO(SO so) {
			this.so = so;
		}

		@Override
		public void run() {
			while (!so.shutdown) {
				try {
					PedidoIO pedido = so.filaIO.take(); // Bloqueia até ter pedido

					if (so.shutdown || pedido.pid == -1)
						break;

					System.out.println("DISPOSITIVO IO: Iniciando operacao para PID " + pedido.pid);
					Thread.sleep(150); // Simula tempo de IO
					ProcessControlBlock pcbDoPedido = so.gp.findProcessInBlockedQueue(pedido.pid);

					if (pcbDoPedido == null) {
						System.err.println("DISPOSITIVO IO: ERRO! Processo PID " + pedido.pid
								+ " nao foi encontrado na fila de bloqueados. O processo pode ter sido terminado. Abortando IO.");
						continue; // Pega o próximo pedido
					}

					int endFisico = so.hw.cpu.translateAddress(pedido.endLogico, pcbDoPedido.pageTable);

					// Se for leitura, insere valor fictício
					if (pedido.isRead) {
						int valorLido = new Random().nextInt(1000); // Simulando leitura de um valor
						so.hw.mem.pos[endFisico].opc = Opcode.DATA;
						so.hw.mem.pos[endFisico].p = valorLido;
						System.out.println("DISPOSITIVO IO: Leitura concluída para PID " + pedido.pid + ". Valor "
								+ valorLido + " escrito no endereco fisico " + endFisico);
					} else { // Escrita
						int valorEscrito = so.hw.mem.pos[endFisico].p;
						System.out.println("DISPOSITIVO IO: Escrita concluída para PID " + pedido.pid
								+ ". Valor lido do endereco fisico " + endFisico + " = " + valorEscrito);
					}

					// Sinaliza interrupção de conclusão de IO
					so.filaInterrupcoesIO.put(pedido.pid);

				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}
			System.out.println("Thread do Dispositivo IO encerrada.");
		}
	}

	// MODIFICADO: Etapa 4 - Thread para tratar interrupções de IO
	public class IOInterruptHandlerThread extends Thread {
		private SO so;

		public IOInterruptHandlerThread(SO so) {
			this.so = so;
		}

		@Override
		public void run() {
			while (!so.shutdown) {
				try {
					Integer completedPid = so.filaInterrupcoesIO.take(); // Bloqueia até ter interrupção
					if (so.shutdown || completedPid == -1)
						break;

					System.out.println("HANDLER DE IO: Recebida interrupcao de conclusao para PID " + completedPid);
					so.gp.unblockProcess(completedPid);

				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}
			System.out.println("Thread do Handler de Interrupções de IO encerrada.");
		}
	}

	public void startSystem() {
		System.out.println("--- Iniciando Threads do Sistema ---");

		// 1. Thread do Dispositivo de I/O (produtor de interrupções)
		DispositivoIO dispositivo = new DispositivoIO(so);
		dispositivo.start();

		// 2. Thread do Handler de Interrupções de I/O (consumidor de interrupções)
		IOInterruptHandlerThread ioInterruptHandler = new IOInterruptHandlerThread(so);
		ioInterruptHandler.start();

		// 3. Thread de Execução da CPU
		so.cpuExecutionManager.start();

		// 4. Thread do Shell (interface com usuário)
		ShellThread shell = new ShellThread();
		shell.start();
	}

	public static void main(String args[]) {
		int memorySize = 1024;
		int pageSize = 16;
		SistemaEscalonador s = new SistemaEscalonador(memorySize, pageSize);
		s.startSystem();
	}

	// --- Program Definitions (unchanged) ---
	public class Program {
		public String name;
		public Word[] image;

		public Program(String n, Word[] i) {
			name = n;
			image = i;
		}
	}

	public class Programs {
		public Program[] progs;

		public Programs() {
			this.progs = new Program[] {
					new Program("fatorialV2", // Este programa usa SYSCALL
							new Word[] {
									new Word(Opcode.LDI, 0, -1, 5),
									new Word(Opcode.STD, 0, -1, 19),
									new Word(Opcode.LDD, 0, -1, 19),
									new Word(Opcode.LDI, 1, -1, -1),
									new Word(Opcode.LDI, 2, -1, 13),
									new Word(Opcode.JMPIL, 2, 0, -1),
									new Word(Opcode.LDI, 1, -1, 1),
									new Word(Opcode.LDI, 6, -1, 1),
									new Word(Opcode.LDI, 7, -1, 13),
									new Word(Opcode.JMPIE, 7, 0, 0),
									new Word(Opcode.MULT, 1, 0, -1),
									new Word(Opcode.SUB, 0, 6, -1),
									new Word(Opcode.JMP, -1, -1, 9),
									new Word(Opcode.STD, 1, -1, 18),
									new Word(Opcode.LDI, 8, -1, 2), // SYSCALL: 2 = OUT
									new Word(Opcode.LDI, 9, -1, 18), // Endereço para OUT
									new Word(Opcode.SYSCALL, -1, -1, -1), // Chama o SO
									new Word(Opcode.STOP, -1, -1, -1),
									new Word(Opcode.DATA, -1, -1, -1),
									new Word(Opcode.DATA, -1, -1, -1)
							}),
					new Program("progMinimo",
							new Word[] {
									new Word(Opcode.LDI, 0, -1, 1),
									new Word(Opcode.LDI, 1, -1, 2),
									new Word(Opcode.LDI, 2, -1, 3),
									new Word(Opcode.LDI, 3, -1, 4),
									new Word(Opcode.LDI, 4, -1, 5),
									new Word(Opcode.LDI, 5, -1, 6),
									new Word(Opcode.LDI, 6, -1, 7),
									new Word(Opcode.LDI, 7, -1, 8),
									new Word(Opcode.LDI, 8, -1, 9),
									new Word(Opcode.STOP, -1, -1, -1),
							}),
					new Program("CPU_Bound", // Programa que apenas usa CPU por um tempo
							new Word[] {
									new Word(Opcode.LDI, 0, -1, 0), // r0 = 0 (contador)
									new Word(Opcode.LDI, 1, -1, 1000), // r1 = 1000 (limite)
									new Word(Opcode.LDI, 2, -1, 1), // r2 = 1 (incremento)
									new Word(Opcode.SUB, 3, 1, 0), // r3 = r1 - r0
									new Word(Opcode.JMPIL, 5, 3, -1), // se r3 < 0, pula para linha 5 (fim)
									new Word(Opcode.JMPIE, 5, 3, -1), // se r3 == 0, pula para linha 5 (fim)
									new Word(Opcode.ADD, 0, 2, -1), // r0 = r0 + r2
									new Word(Opcode.JMP, -1, -1, 3), // volta para o teste
									new Word(Opcode.STOP, -1, -1, -1), // linha 8 (fim)
							})
			};
		}

		public Word[] retrieveProgram(String pname) {
			if (pname == null)
				return null;
			for (Program p : progs) {
				if (p != null && pname.equals(p.name))
					return p.image;
			}
			return null;
		}

		public String getAvailableProgramNames() {
			List<String> names = new ArrayList<>();
			for (Program p : progs) {
				if (p != null)
					names.add(p.name);
			}
			return String.join(", ", names);
		}
	}

	public class PedidoIO {
		public int pid;
		public int endLogico;
		public boolean isRead;

		public PedidoIO(int pid, int endLogico, boolean isRead) {
			this.pid = pid;
			this.endLogico = endLogico;
			this.isRead = isRead;
		}
	}

}