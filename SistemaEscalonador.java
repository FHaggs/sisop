import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.FileNotFoundException;

public class SistemaEscalonador {

	// --- Configuration ---
	// Define the time slice quantum (number of instructions)
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
		SYSCALL, STOP, NOP
	}

	// --- UPDATED Interrupts Enum ---
	public enum Interrupts {
		noInterrupt, intEnderecoInvalido, intInstrucaoInvalida, intOverflow,
		intSTOP, // STOP instruction treated as an interrupt source
		intTempo, // Timer interrupt for preemption
		intIO; // NEW: I/O operation completion interrupt
	}

	// --- UPDATED CPU Class ---
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

		private boolean cpuStop; // General stop flag (used by handlers to stop CPU)
		private boolean debug; // Trace flag
		private boolean runningNOP; // NEW: Flag to indicate if running NOP program

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
			cpuStop = false; // Changed to false to keep CPU running
			runningNOP = false;
		}

		public void setAddressOfHandlers(InterruptHandling _ih, SysCallHandling _sysCall) {
			ih = _ih;
			sysCall = _sysCall;
		}

		public void setUtilities(Utilities _u) {
			u = _u;
		}

		// Check physical address validity
		private boolean legal(int e) {
			if (e >= 0 && e < m.length) {
				return true;
			} else {
				irpt = Interrupts.intEnderecoInvalido;
				System.err.println(">>> ERRO CPU: Endereco fisico invalido: " + e);
				return false;
			}
		}

		// Check for integer overflow
		private boolean testOverflow(int v) {
			if ((v < minInt) || (v > maxInt)) {
				irpt = Interrupts.intOverflow;
				System.err.println(">>> ERRO CPU: Overflow com valor: " + v);
				return false;
			}
			return true;
		}

		// Set CPU context for a specific process
		public void setContext(int _pc, List<Integer> _page_table, int[] _regs) {
			pc = _pc;
			page_table = _page_table;
			System.arraycopy(_regs, 0, this.reg, 0, _regs.length);
			irpt = Interrupts.noInterrupt;
			cycleCounter = 0; // Reset cycle counter for new context/slice
			cpuStop = false; // Ensure CPU is ready to run
			runningNOP = false;
			if (debug) {
				System.out.println("CPU: Contexto carregado - PC_log=" + pc + " Paginas=" + page_table);
			}
		}

		// Translate logical address to physical address
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

			// 'legal' check will happen before memory access anyway
			// if (!legal(physicalAddress)) return -1; // Already handles irpt

			return physicalAddress;
		}

		// --- Getters for Context Saving ---
		public int getPC() {
			return pc;
		}

		public int[] getRegs() {
			// Return a copy to be safe, especially if threading is added later
			return Arrays.copyOf(reg, reg.length);
		}

		// --- Setter for Trace Flag ---
		public void setDebug(boolean _debug) {
			this.debug = _debug;
			System.out.println("CPU: Modo trace " + (this.debug ? "ativado." : "desativado."));
		}

		// --- Public method to signal CPU should stop (e.g., by scheduler or error)
		// ---
		public void stopCPU() {
			if (!runningNOP) {
				startNOPProgram();
			}
		}

		// --- The main CPU execution cycle ---
		// Now runs instructions until an interrupt occurs or cpuStop is set externally
		public void run() {
			if (page_table == null) {
				startNOPProgram();
			}

			cpuStop = false; // Set running state

			// Main fetch-decode-execute cycle
			while (!cpuStop && irpt == Interrupts.noInterrupt) { // Run while no stop signal and no pending interrupt

				// 1. Fetch Instruction
				int physicalPC = translateAddress(pc);
				if (irpt != Interrupts.noInterrupt)
					break; // Stop if address translation failed
				if (!legal(physicalPC))
					break; // Stop if physical PC is invalid (irpt set by legal)

				ir = m[physicalPC];

				// --- Debug Output ---
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
				Opcode currentOpc = ir.opc; // Store opcode in case pc changes mid-instruction
				int originalPC = pc; // Store PC before potential modification

				switch (currentOpc) {
					// ... (Instruction implementations - largely unchanged, but ensure PC is
					// incremented correctly) ...
					// --- Data Transfer ---
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
								System.err.println(">>> ERRO CPU: LDD tentando ler de posicao de codigo em end logico "
										+ logicalAddress);
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
								System.err.println(">>> ERRO CPU: LDX tentando ler de posicao de codigo em end logico "
										+ logicalAddress);
								irpt = Interrupts.intInstrucaoInvalida;
							}
						}
						break;
					}
					case STD: {
						int logicalAddress = ir.p;
						int physicalAddress = translateAddress(logicalAddress);
						if (irpt == Interrupts.noInterrupt && legal(physicalAddress)) {
							// Allow storing over code or data? Assuming yes for now.
							m[physicalAddress].opc = Opcode.DATA; // Mark as data after write
							m[physicalAddress].p = reg[ir.ra];
							pc++;
							if (debug) {
								System.out.print("        STD M[" + physicalAddress + "] <- " + reg[ir.ra] + " ");
								u.dump(m[physicalAddress]);
							}
						}
						break;
					}
					case STX: {
						int logicalAddress = reg[ir.ra];
						int physicalAddress = translateAddress(logicalAddress);
						if (irpt == Interrupts.noInterrupt && legal(physicalAddress)) {
							m[physicalAddress].opc = Opcode.DATA; // Mark as data
							m[physicalAddress].p = reg[ir.rb];
							pc++;
							if (debug) {
								System.out.print("        STX M[" + physicalAddress + "] <- " + reg[ir.ra] + " ");
								u.dump(m[physicalAddress]);
							}
						}
						break;
					}
					case MOVE:
						reg[ir.ra] = reg[ir.rb];
						pc++;
						break;

					// --- Arithmetic ---
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

					// --- Jumps (Update logical PC only) ---
					case JMP:
						pc = ir.p;
						break;
					case JMPI:
						pc = reg[ir.ra];
						break;
					case JMPIG:
						if (reg[ir.rb] > 0) {
							pc = reg[ir.ra];
						} else {
							pc++;
						}
						break;
					case JMPIL:
						if (reg[ir.rb] < 0) {
							pc = reg[ir.ra];
						} else {
							pc++;
						}
						break;
					case JMPIE:
						if (reg[ir.rb] == 0) {
							pc = reg[ir.ra];
						} else {
							pc++;
						}
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
						if (reg[ir.rb] > 0) {
							pc = ir.p;
						} else {
							pc++;
						}
						break;
					case JMPILK:
						if (reg[ir.rb] < 0) {
							pc = ir.p;
						} else {
							pc++;
						}
						break;
					case JMPIEK:
						if (reg[ir.rb] == 0) {
							pc = ir.p;
						} else {
							pc++;
						}
						break;
					case JMPIGT:
						if (reg[ir.ra] > reg[ir.rb]) {
							pc = ir.p;
						} else {
							pc++;
						}
						break;

					// --- System Call & Stop ---
					case SYSCALL:
						sysCall.handle(); // SysCall handler might set irpt
						if (irpt == Interrupts.noInterrupt) { // Only increment PC if syscall didn't cause interrupt
							pc++;
						}
						break;

					case STOP:
						System.out.println(">>> CPU: Executando STOP. PC FISICO: " + physicalPC);
						irpt = Interrupts.intSTOP; // Signal STOP interrupt
						// Don't increment PC after stop
						break;

					// --- Invalid/Data Opcodes ---
					case DATA:
					case NOP:
						break;
					default:
						irpt = Interrupts.intInstrucaoInvalida;
						System.err.println(">>> ERRO CPU: Opcode invalido (" + currentOpc + ") encontrado em PC logico "
								+ originalPC);
						break;
				} // End Switch

				// 3. Check for Timer Interrupt (if no other interrupt occurred)
				if (irpt == Interrupts.noInterrupt && !cpuStop) {
					cycleCounter++;
					if (cycleCounter >= quantum) {
						irpt = Interrupts.intTempo; // Signal timer interrupt
						if (debug)
							System.out.println("CPU: Quantum (" + quantum + ") atingido. Gerando intTempo.");
						// cycleCounter is reset when context is set for the *next* slice
					}
				}

				// If an interrupt was generated (by instruction or timer), the loop condition
				// (irpt == noInterrupt) will fail, and the loop will exit.

			} // End of while loop

			// 4. Handle Pending Interrupt (after loop termination)
			if (irpt != Interrupts.noInterrupt) {
				ih.handle(irpt, pc); // Pass the PC where the interrupt occurred
				// The handler is now responsible for context switching/stopping
			} else if (cpuStop) {
				// If stopped externally (e.g., by scheduler if no processes left)
				if (debug)
					System.out.println("CPU: Execucao parada externamente.");
			}
			// If loop exited normally (which shouldn't happen with STOP/errors handled),
			// it implies an issue.

			// cpuStop = true; // Ensure CPU is marked as stopped after run cycle finishes
		} // End of run()

		// NEW: Method to start NOP program
		private void startNOPProgram() {
			if (!runningNOP) {
				runningNOP = true;
				page_table = new ArrayList<>();
				page_table.add(0); // Use first frame for NOP program
				pc = 0;
				// Create NOP program in memory if not already there
				if (m[0].opc != Opcode.NOP) {
					m[0] = new Word(Opcode.NOP, -1, -1, -1); // NOP instruction
				}
				if (debug) {
					System.out.println("CPU: Iniciando programa NOP");
				}
			}
		}
	}

	// --- UPDATED InterruptHandling Class ---
	public class InterruptHandling {
		private CPU cpu;
		private SO so;

		public InterruptHandling(SO _so) {
			this.so = _so;
			this.cpu = _so.hw.cpu;
		}

		public void handle(Interrupts irpt, int interruptedPC) {
			ProcessControlBlock currentProcess = so.gp.getRunningProcess();

			System.out.println("-----------------------------------------------------");
			System.out.println(
					">>> INTERRUPCAO: " + irpt + " ocorrida em PC logico: " + interruptedPC + " (Processo PID: "
							+ (currentProcess != null ? currentProcess.pid : "N/A") + ")");

			switch (irpt) {
				case intTempo:
					// Time slice end - Preemption
					if (currentProcess != null) {
						System.out.println("GP: Quantum expirado para PID " + currentProcess.pid);
						so.gp.saveContext(currentProcess);
						so.gp.addToReadyQueue(currentProcess);
						so.gp.schedule();
					} else {
						System.err.println("IH: Erro - intTempo mas nenhum processo estava rodando?");
						cpu.stopCPU();
					}
					break;

				case intSTOP:
					// Process requested termination
					if (currentProcess != null) {
						System.out.println("GP: Processo PID " + currentProcess.pid + " executou STOP.");
						so.gp.terminateProcess(currentProcess);
						so.gp.schedule();
					} else {
						System.err.println("IH: Erro - intSTOP mas nenhum processo estava rodando?");
						cpu.stopCPU();
					}
					break;

				case intIO:
					// I/O operation completed
					if (so.ioCompletedRequest != null) {
						System.out.println("IH: Operacao de I/O completada para PID " + so.ioCompletedRequest.pid);

						// Find the blocked process
						ProcessControlBlock blockedProcess = null;
						for (ProcessControlBlock pcb : so.gp.bloqueados) {
							if (pcb.pid == so.ioCompletedRequest.pid) {
								blockedProcess = pcb;
								break;
							}
						}

						if (blockedProcess != null) {
							// Unblock the process
							so.gp.desbloqueiaProcesso(blockedProcess);
							System.out.println("IH: Processo PID " + blockedProcess.pid + " desbloqueado.");
							
							// If no process is running, schedule the unblocked process
							if (so.gp.getRunningProcess() == null) {
								so.gp.schedule();
							}
						} else {
							System.err.println("IH: Erro - Processo PID " + so.ioCompletedRequest.pid + " nao encontrado na fila de bloqueados.");
						}

						// Clear the completed request
						so.ioCompletedRequest = null;
					}
					break;

				case intEnderecoInvalido:
				case intInstrucaoInvalida:
				case intOverflow:
					// Error interrupts - terminate the process
					if (currentProcess != null) {
						System.out.println(
								"GP: Processo PID " + currentProcess.pid + " terminado por erro (" + irpt + ").");
						so.gp.terminateProcess(currentProcess);
						so.gp.schedule();
					} else {
						System.err.println("IH: Erro - " + irpt + " mas nenhum processo estava rodando?");
						cpu.stopCPU();
					}
					break;

				default:
					System.err.println("IH: Interrupcao desconhecida: " + irpt);
					cpu.stopCPU();
					break;
			}
		}
	}

	// --- UPDATED SysCallHandling Class ---
	// STOP is now handled by the Interrupt Handler (intSTOP)
	// Other syscalls remain similar
	public class SysCallHandling {
		private CPU cpu;
		private Utilities utils;
		private Memory mem;
		private SO so;

		public SysCallHandling(SO _so) {
			this.so = _so;
			this.cpu = _so.hw.cpu;
			this.utils = _so.utils;
			this.mem = _so.hw.mem;
		}

		public void handle() {
			int operation = cpu.reg[8]; // Syscall code in R8
			int arg = cpu.reg[9]; // Argument in R9

			System.out.print(">>> SYSCALL: Processo PID "
					+ (so.gp.getRunningProcess() != null ? so.gp.getRunningProcess().pid : "N/A") + " Operacao="
					+ operation);

			switch (operation) {
				case 1: // Input
					System.out.println(" (Input Request)");
					// Create I/O request and add to device queue
					IORequest request = new IORequest(
							so.gp.getRunningProcess().pid,
							1, // Input operation
							arg, // Memory address to store input
							0 // No value for input
					);
					so.device.addRequest(request);
					// Block the process
					so.gp.bloqueiaProcesso(so.gp.getRunningProcess());
					// Schedule next process
					so.gp.schedule();
					break;

				case 2: // Output
					System.out.println(" (Output Request)");
					// Get value from memory
					int physicalAddress = cpu.translateAddress(arg);
					if (cpu.irpt == Interrupts.noInterrupt && cpu.legal(physicalAddress)) {
						int value = mem.pos[physicalAddress].p;
						// Create I/O request and add to device queue
						IORequest outRequest = new IORequest(
								so.gp.getRunningProcess().pid,
								2, // Output operation
								arg, // Memory address
								value // Value to output
						);
						so.device.addRequest(outRequest);
						// Block the process
						so.gp.bloqueiaProcesso(so.gp.getRunningProcess());
						// Schedule next process
						so.gp.schedule();
					} else {
						System.err.println("    Falha no Output: endereco invalido.");
						cpu.irpt = Interrupts.intEnderecoInvalido;
					}
					break;

				default:
					System.err.println("    Operacao de sistema desconhecida: " + operation);
					cpu.irpt = Interrupts.intInstrucaoInvalida;
					break;
			}
		}
	}

	// ... (Utilities class remains largely the same) ...
	public class Utilities {
		private HW hw;
		private SO so;

		public Utilities(HW _hw, SO _so) {
			hw = _hw;
			so = _so;
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

		public void dumpMemoryForProcess(List<Integer> pageTable) {
			System.out.println("--- Dump da Memoria Paginada do Processo (Visao Logica -> Fisica) ---");
			int pageSize = hw.pageSize;
			Word[] memory = hw.mem.pos;

			if (pageTable == null || pageTable.isEmpty()) {
				System.out.println("  Tabela de paginas vazia ou invalida.");
				System.out.println("--------------------------------------------------");
				return;
			}

			for (int pageIndex = 0; pageIndex < pageTable.size(); pageIndex++) {
				Integer frameNumber = pageTable.get(pageIndex);
				if (frameNumber != null) {
					int frameStart = frameNumber * pageSize;
					int frameEnd = Math.min(frameStart + pageSize, memory.length);
					System.out.println("  Pagina Logica " + pageIndex + " (End. Logicos " + (pageIndex * pageSize) + "-"
							+ ((pageIndex + 1) * pageSize - 1) + ")"
							+ " -> Frame Fisico " + frameNumber + " (End. Fisicos " + frameStart + "-" + (frameEnd - 1)
							+ ")");

					for (int addr = frameStart; addr < frameEnd; addr++) {
						System.out.printf("    Fis: %04d: ", addr);
						dump(memory[addr]);
						System.out.println();
					}
				} else {
					System.out.println("  Pagina Logica " + pageIndex + " -> Nao Mapeada (Frame=null)");
				}
			}
			System.out.println("--------------------------------------------------");
		}

		public boolean loadProgramToMemory(Word[] program, List<Integer> pageTable) {
			int programSize = program.length;
			int pageSize = hw.pageSize;
			Word[] memory = hw.mem.pos;

			// System.out.println("UTILS: Carregando " + programSize + " palavras usando
			// tabela: " + pageTable); // Verbose

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
					// Direct copy of word fields
					memory[physicalAddress].opc = program[i].opc;
					memory[physicalAddress].ra = program[i].ra;
					memory[physicalAddress].rb = program[i].rb;
					memory[physicalAddress].p = program[i].p;
				} else {
					System.err.println("UTILS: Erro ao carregar - Endereco fisico calculado invalido: "
							+ physicalAddress + " para endereco logico " + logicalAddress);
					return false;
				}
			}
			// System.out.println("UTILS: Programa carregado com sucesso."); // Verbose
			return true;
		}
	}

	// ... (Contexto class remains the same) ...
	public class Contexto {
		public int[] regs;
		public int pc;

		public Contexto() {
			this.pc = 0;
			this.regs = new int[10];
			Arrays.fill(this.regs, 0);
		}
	}

	// ... (ProcessControlBlock class remains the same) ...
	public class ProcessControlBlock {
		public int pid;
		public List<Integer> pageTable;
		public Contexto contexto;
		public String programName;
		// NEW: State could be added (e.g., READY, RUNNING, TERMINATED) if needed
		// public enum ProcessState { READY, RUNNING, TERMINATED }
		// public ProcessState state;

		public ProcessControlBlock(int pid, List<Integer> pageTable, String programName) {
			this.pid = pid;
			this.pageTable = pageTable;
			this.programName = programName;
			this.contexto = new Contexto(); // PC=0, regs=0
			// this.state = ProcessState.READY; // Initialize as ready
		}
	}

	// --- UPDATED ProcessManagement Class ---
	public class ProcessManagement {

		private LinkedList<ProcessControlBlock> aptos; // Ready queue (FIFO)
		private LinkedList<ProcessControlBlock> bloqueados; // Blocked queue (FIFO)
		private ProcessControlBlock running; // Currently running process PCB
		private CPU cpu;
		private MemoryManagment mm;
		private Utilities utils;
		// private HW hw; // Not directly needed if pageSize comes from CPU/MM

		private AtomicInteger nextPid = new AtomicInteger(0);
		private boolean schedulerActive = false; // Flag to control the execAll loop

		// Removed HW from constructor params as it's accessible via cpu/mm/utils if
		// needed
		public ProcessManagement(CPU _cpu, MemoryManagment _mm, Utilities _utils) {
			this.aptos = new LinkedList<>();
			this.bloqueados = new LinkedList<>(); // Initialize blocked queue
			this.running = null;
			this.cpu = _cpu;
			this.mm = _mm;
			this.utils = _utils;
			// this.hw = _hw;
		}

		// --- Get the currently running process ---
		public ProcessControlBlock getRunningProcess() {
			return running;
		}

		// --- Create Process ---
		public boolean criaProcesso(Word[] programa, String programName) {
			if (programa == null || programa.length == 0) {
				System.out.println("GP: Erro - Programa invalido ou vazio.");
				return false;
			}
			int programSize = programa.length;
			System.out
					.println("GP: Tentando criar processo para '" + programName + "' (" + programSize + " palavras).");

			List<Integer> pageTable = new ArrayList<>();
			if (!mm.aloca(programSize, pageTable)) {
				System.out.println("GP: Falha ao criar processo - Memoria insuficiente.");
				return false;
			}
			// System.out.println("GP: Memoria alocada. Tabela: " + pageTable); // Verbose

			int pid = nextPid.getAndIncrement();
			ProcessControlBlock newPCB = new ProcessControlBlock(pid, pageTable, programName);

			if (!utils.loadProgramToMemory(programa, pageTable)) {
				System.err.println(
						"GP: Falha ao carregar o programa na memoria para PID " + pid + ". Desalocando memoria.");
				mm.desaloca(pageTable);
				return false;
			}
			System.out.println("GP: Programa carregado na memoria para PID " + pid);

			addToReadyQueue(newPCB); // Add to ready queue using the synchronized method
			System.out.println(
					"GP: Processo '" + programName + "' (PID " + pid + ") criado e adicionado a fila de aptos.");
			return true;
		}

		// --- Add to Ready Queue (potentially synchronized if threaded) ---
		// @synchronized (if needed)
		public void addToReadyQueue(ProcessControlBlock pcb) {
			if (pcb != null) {
				aptos.addLast(pcb); // Add to the end of the list (FIFO)
				// pcb.state = ProcessState.READY;
				if (cpu.debug)
					System.out.println("GP: Processo PID " + pcb.pid + " adicionado/retornado a fila de aptos.");
			}
		}

		// --- Save Context of a Process ---
		public void saveContext(ProcessControlBlock pcb) {
			if (pcb != null) {
				pcb.contexto.pc = cpu.getPC();
				// getRegs returns a copy, so direct assignment is ok
				pcb.contexto.regs = cpu.getRegs();
				if (cpu.debug) {
					System.out.println("GP: Contexto salvo para PID " + pcb.pid + " (PC=" + pcb.contexto.pc + ")");
				}
			}
		}

		// --- Terminate Process (called by interrupt handler) ---
		public void terminateProcess(ProcessControlBlock pcb) {
			if (pcb != null) {
				System.out.println("GP: Terminando processo PID: " + pcb.pid + " ('" + pcb.programName + "')");
				// 1. Deallocate memory
				mm.desaloca(pcb.pageTable);
				// 2. Ensure it's removed from 'running' state
				if (running == pcb) {
					running = null;
				}
				// 3. (Optional) Mark as terminated if state enum exists
				// pcb.state = ProcessState.TERMINATED;
				// 4. PCB might be kept for a short while for cleanup/stats, or removed
				// immediately.
				// Here we just nullify 'running' and memory is freed.
				System.out.println("GP: Recursos desalocados para PID " + pcb.pid);
			}
		}

		// --- Explicit Deallocation Command (rm) ---
		public void desalocaProcesso(int pid) {
			System.out.println("GP: Tentando desalocar processo PID: " + pid + " (comando rm)");
			ProcessControlBlock pcbToTerminate = null;

			// Is it the running process?
			if (running != null && running.pid == pid) {
				System.out.println("GP: Processo " + pid + " esta rodando. Sera terminado e desalocado.");
				pcbToTerminate = running;
				// Need to stop CPU and let scheduler run next, similar to STOP interrupt
				// For simplicity here, we'll just terminate and let subsequent schedule call
				// handle it.
				saveContext(pcbToTerminate); // Save final context just in case
				terminateProcess(pcbToTerminate); // Deallocate memory, sets running=null
				cpu.stopCPU(); // Ensure CPU stops current execution if it was this process
				schedule(); // Try to schedule next immediately
				return;
			}

			// Search in the ready queue
			Iterator<ProcessControlBlock> iterator = aptos.iterator();
			while (iterator.hasNext()) {
				ProcessControlBlock pcb = iterator.next();
				if (pcb.pid == pid) {
					System.out.println("GP: Processo " + pid + " encontrado na fila de aptos. Sera desalocado.");
					pcbToTerminate = pcb;
					iterator.remove(); // Remove from ready queue
					break;
				}
			}

			if (pcbToTerminate != null) {
				terminateProcess(pcbToTerminate); // Deallocate memory
			} else {
				System.out.println("GP: Processo PID " + pid + " nao encontrado para desalocacao (rm).");
			}
		}

		// --- The Scheduler ---
		// Decides who runs next and sets the CPU context.
		// Called by interrupt handler or when a process terminates/blocks.
		public void schedule() {
			if (cpu.debug)
				System.out.println("GP: Escalonador ativado.");

			// If a process was running, it should have been saved and possibly moved to
			// ready queue by the caller (InterruptHandler)

			if (!aptos.isEmpty()) {
				// Get the next process from the ready queue (FIFO)
				running = aptos.removeFirst();
				// running.state = ProcessState.RUNNING;

				System.out.println("GP: Escalonando proximo processo -> PID: " + running.pid + " ('"
						+ running.programName + "')");

				// Load context into CPU
				cpu.setContext(running.contexto.pc, running.pageTable, running.contexto.regs);
				// cpu.run() will be called by the main execution loop (or execAll loop)
			} else {
				// No processes ready to run
				running = null;
				System.out.println("GP: Fila de aptos VAZIA. Nenhum processo para escalonar.");
				cpu.stopCPU(); // Signal CPU to stop if no one is running
				// schedulerActive = false; // Stop the execAll loop if it's running
			}
		}

		public void bloqueiaProcesso(ProcessControlBlock pcb) {
			if (pcb != null) {
				System.out.println("GP: Bloqueando processo PID: " + pcb.pid + " ('" + pcb.programName + "')");
				// 1. Save context
				saveContext(pcb);
				// 2. Remove from running or ready queue
				if (running == pcb) {
					running = null; // Clear running if it was the current process
				} else {
					aptos.remove(pcb); // Remove from ready queue if present
				}
				// 3. Add to blocked queue
				bloqueados.addLast(pcb);
				// Schedule next process if this was the running process
				if (running == null) {
					schedule();
				}
			}
		}

		public void desbloqueiaProcesso(ProcessControlBlock pcb) {
			if (pcb != null) {
				System.out.println("GP: Desbloqueando processo PID: " + pcb.pid + " ('" + pcb.programName + "')");
				// 1. Remove from blocked queue
				bloqueados.remove(pcb);
				// 2. Add to ready queue
				addToReadyQueue(pcb);
				// If no process is running, schedule this one
				if (running == null) {
					schedule();
				}
			}
		}

		// --- exec <id> (Starts a specific process, assumes it will be preempted) ---
		// This command becomes less useful in a preemptive system started by execAll
		// It could potentially force a specific process to run next, but that violates
		// RR.
		// Let's make it just ensure the process is ready and let the scheduler pick it
		// up.
		public void exec(int pid) {
			System.out.println("GP: Comando 'exec " + pid
					+ "' em modo escalonado. Processo sera colocado no inicio da fila de aptos se existir.");

			ProcessControlBlock pcbToRun = null;
			int index = -1;

			// Check if running
			if (running != null && running.pid == pid) {
				System.out.println("GP: Processo " + pid + " ja esta rodando.");
				return;
			}

			// Find in ready queue
			for (int i = 0; i < aptos.size(); i++) {
				if (aptos.get(i).pid == pid) {
					pcbToRun = aptos.get(i);
					index = i;
					break;
				}
			}

			if (pcbToRun != null) {
				if (index > 0) { // If not already at the front
					aptos.remove(index);
					aptos.addFirst(pcbToRun); // Move to front to run sooner (violates strict FIFO but matches command
												// intent)
					System.out.println("GP: Processo PID " + pid + " movido para o inicio da fila de aptos.");
				} else {
					System.out.println("GP: Processo PID " + pid + " ja esta no inicio da fila de aptos.");
				}
				// If scheduler isn't active (e.g., system idle), maybe kickstart it?
				if (!schedulerActive && running == null) {
					System.out.println("GP: Iniciando escalonador pois estava ocioso.");
					schedulerActive = true;
					schedule(); // Load the first process
					// In a non-threaded model, we need a loop here or rely on execAll
					// For now, just scheduling it is enough, execAll will run it.
				}
			} else {
				System.out.println("GP: Processo PID " + pid + " nao encontrado ou ja terminado.");
			}
		}

		// --- execAll (Simulates continuous execution until all processes finish) ---
		public void execAll() {
			System.out.println("\n--- GP: Iniciando execucao escalonada (execAll) ---");
			if (running == null && aptos.isEmpty()) {
				System.out.println("GP: Nenhum processo pronto para executar.");
				return;
			}

			schedulerActive = true;

			// If no process is currently running, schedule the first one
			if (running == null) {
				schedule();
			}

			// Main simulation loop for execAll - runs until scheduler stops
			while (schedulerActive && running != null) {
				if (cpu.debug)
					System.out.println(
							"GP: Loop execAll - Chamando cpu.run() para PID " + running.pid + " (PC=" + cpu.pc + ")");

				cpu.run(); // Run the current process for its time slice (or until STOP/error)

				// After cpu.run() finishes, an interrupt should have occurred (Tempo, STOP,
				// Error)
				// The InterruptHandler already called schedule() to load the next process (or
				// stop if none)
				// We just continue the loop if the scheduler is still active and found someone
				// to run.
				if (cpu.debug && running != null) {
					System.out.println("GP: Loop execAll - Retornou da cpu.run(). Proximo e PID " + running.pid);
				} else if (cpu.debug && running == null) {
					System.out.println("GP: Loop execAll - Retornou da cpu.run(). Nenhum processo restante.");
				}
			}

			System.out.println("--- GP: Execucao escalonada (execAll) terminada ---");
			schedulerActive = false; // Ensure flag is reset
			cpu.stopCPU(); // Make sure CPU is marked as stopped
		}

		// --- List Processes ---
		public void listProcesses() {
			System.out.println("\n--- Lista de Processos ---");
			System.out.println("Processo em execucao: "
					+ (running != null ? "PID " + running.pid + " ('" + running.programName + "')" : "Nenhum"));

			System.out.println("\nFila de Prontos:");
			if (aptos.isEmpty()) {
				System.out.println("  (Vazia)");
			} else {
				for (ProcessControlBlock pcb : aptos) {
					System.out.println("  PID " + pcb.pid + " ('" + pcb.programName + "')");
				}
			}

			System.out.println("\nFila de Bloqueados:");
			if (bloqueados.isEmpty()) {
				System.out.println("  (Vazia)");
			} else {
				for (ProcessControlBlock pcb : bloqueados) {
					System.out.println("  PID " + pcb.pid + " ('" + pcb.programName + "')");
				}
			}
			System.out.println("------------------------\n");
		}

		// --- Dump Process Info ---
		public void dumpProcess(int pid) {
			ProcessControlBlock pcbToDump = null;

			if (running != null && running.pid == pid) {
				pcbToDump = running;
				System.out.println("--- Dump do Processo (Running) PID: " + pid + " ---");
			} else {
				for (ProcessControlBlock pcb : aptos) {
					if (pcb.pid == pid) {
						pcbToDump = pcb;
						System.out.println("--- Dump do Processo (Ready) PID: " + pid + " ---");
						break;
					}
				}
			}

			if (pcbToDump != null) {
				System.out.println("Nome do Programa: '" + pcbToDump.programName + "'");
				System.out.println("Estado: " + (pcbToDump == running ? "Running" : "Ready"));
				System.out.println("Program Counter (PC): " + pcbToDump.contexto.pc);
				System.out.print("Registradores: ");
				if (pcbToDump.contexto.regs != null) {
					for (int i = 0; i < pcbToDump.contexto.regs.length; i++) {
						System.out.print("R" + i + "=" + pcbToDump.contexto.regs[i]
								+ (i == pcbToDump.contexto.regs.length - 1 ? "" : " | "));
					}
					System.out.println();
				} else {
					System.out.println("N/A");
				}
				System.out.println("Tabela de Páginas (Frame Numbers): "
						+ (pcbToDump.pageTable != null ? pcbToDump.pageTable.toString() : "N/A"));

				if (pcbToDump.pageTable != null) {
					utils.dumpMemoryForProcess(pcbToDump.pageTable);
				} else {
					System.out.println("Nao foi possivel fazer dump da memoria (tabela de paginas invalida).");
				}
				System.out.println("-------------------------------------------");

			} else {
				System.out.println("GP: Processo PID " + pid + " nao encontrado para dump.");
			}
		}

	} // --- Fim do ProcessManagement ---

	// ... (MemoryManagment class remains the same) ...
	public class MemoryManagment {
		private Set<Integer> freeFrames;
		private int frameSize;
		private int totalFrames;
		private int memSize;

		public MemoryManagment(int tamMem, int tamFrame) {
			this.memSize = tamMem;
			this.frameSize = tamFrame;
			this.totalFrames = tamMem / tamFrame;
			this.freeFrames = new HashSet<>();

			// System.out.println("GM: Inicializando com " + totalFrames + " frames de
			// tamanho " + tamFrame + " (Total Mem: " + tamMem + ")"); // Verbose
			for (int i = 0; i < totalFrames; i++) {
				freeFrames.add(i);
			}
			System.out.println("GM: Frames livres iniciais: " + freeFrames.size());
		}

		public boolean aloca(int numPalavras, List<Integer> pageTable) {
			int numFramesNeeded = (int) Math.ceil((double) numPalavras / frameSize);
			// System.out.println("GM: Pedido de alocacao para " + numPalavras + " palavras
			// (" + numFramesNeeded + " frames). Livres: " + freeFrames.size()); // Verbose

			if (freeFrames.size() < numFramesNeeded) {
				System.out.println("GM: Erro - Nao ha frames livres suficientes.");
				return false;
			}

			Iterator<Integer> iterator = freeFrames.iterator();
			List<Integer> allocatedFrames = new ArrayList<>();

			for (int i = 0; i < numFramesNeeded; i++) {
				if (iterator.hasNext()) {
					int frameNumber = iterator.next();
					allocatedFrames.add(frameNumber);
					iterator.remove();
				} else {
					System.err.println("GM: Erro inesperado - Faltaram frames durante a alocacao!");
					freeFrames.addAll(allocatedFrames); // Rollback
					pageTable.clear();
					return false;
				}
			}

			pageTable.addAll(allocatedFrames);
			// System.out.println("GM: Alocacao OK. Frames: " + allocatedFrames + ". Livres
			// restantes: " + freeFrames.size()); // Verbose
			return true;
		}

		public void desaloca(List<Integer> pageTable) {
			if (pageTable == null || pageTable.isEmpty()) {
				// System.out.println("GM: Aviso - Tentativa de desalocar com tabela nula ou
				// vazia."); // Verbose
				return;
			}
			System.out.print("GM: Desalocando frames da tabela: " + pageTable + ". Frames liberados: ");
			int count = 0;
			List<Integer> framesToFree = new ArrayList<>(pageTable); // Copy to avoid concurrent modification if needed
			pageTable.clear(); // Clear the original table immediately

			for (Integer frameNumber : framesToFree) {
				if (frameNumber != null) {
					if (freeFrames.add(frameNumber)) {
						System.out.print(frameNumber + " ");
						count++;
					} else {
						System.err.print("(Aviso: Frame " + frameNumber + " ja estava livre?) ");
					}
				}
			}
			System.out.println("\nGM: Total de " + count + " frames retornados ao conjunto livre. Frames livres agora: "
					+ freeFrames.size());
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
			return memSize;
		}
	} // --- Fim do MemoryManagment ---

	// --- UPDATED SO (Sistema Operacional) Class ---
	public class SO {
		public InterruptHandling ih;
		public SysCallHandling sc;
		public Utilities utils;
		public MemoryManagment mm;
		public ProcessManagement gp;
		public HW hw;
		public Device device;
		public IORequest ioCompletedRequest;
		private Scanner shellInput; // NEW: Scanner for shell input

		public SO(HW _hw) {
			hw = _hw;
			ih = new InterruptHandling(this);
			sc = new SysCallHandling(this);
			utils = new Utilities(hw, this);
			
			mm = new MemoryManagment(hw.mem.getSize(), hw.pageSize);
			gp = new ProcessManagement(hw.cpu, mm, utils);
			device = new Device(hw.cpu, this);
			shellInput = new Scanner(System.in); // NEW: Initialize shell input
			hw.cpu.setAddressOfHandlers(ih, sc);
			hw.cpu.setUtilities(utils);
		}

		// NEW: Method to get shell input
		public String getShellInput() {
			return shellInput.nextLine();
		}
	}

	// --- Sistema class members ---
	public HW hw;
	public SO so;
	public Programs progs;

	// --- Sistema Constructor ---
	public SistemaEscalonador(int tamMem, int page_size) {
		// Pass DELTA_T to HW/CPU constructor
		hw = new HW(tamMem, page_size, DELTA_T);
		so = new SO(hw);
		progs = new Programs();
		System.out.println("Sistema: Hardware e SO criados. Pronto para comandos.");
	}

	// --- UPDATED HW Class ---
	// Needs to pass quantum to CPU
	public class HW {
		public Memory mem;
		public CPU cpu;
		public int pageSize;

		public HW(int tamMem, int _pageSize, int _quantum) { // Added quantum
			mem = new Memory(tamMem);
			pageSize = _pageSize;
			// Pass quantum to CPU
			cpu = new CPU(mem, false, pageSize, _quantum);
		}
	}

	// --- UPDATED Interactive Command Shell ---
	public void runV2() {
		System.out.println("Sistema Operacional iniciado. Digite 'help' para ver os comandos disponiveis.");
		System.out.println("Programas disponiveis: " + progs.getAvailableProgramNames());

		while (true) {
			try {
				System.out.print("\nSO> ");
				String command = so.getShellInput();
				if (command == null) continue;

				String[] parts = command.trim().split("\\s+");
				if (parts.length == 0) continue;

				switch (parts[0]) {
					case "new":
						if (parts.length != 2) {
							System.out.println("Uso: new <nomePrograma>");
							break;
						}
						Word[] programa = progs.retrieveProgram(parts[1]);
						if (programa != null) {
							so.gp.criaProcesso(programa, parts[1]);
						} else {
							System.out.println("Programa '" + parts[1] + "' nao encontrado.");
						}
						break;

					case "rm":
						if (parts.length != 2) {
							System.out.println("Uso: rm <pid>");
							break;
						}
						try {
							int pid = Integer.parseInt(parts[1]);
							so.gp.desalocaProcesso(pid);
						} catch (NumberFormatException e) {
							System.out.println("PID deve ser um numero inteiro.");
						}
						break;

					case "ps":
						so.gp.listProcesses();
						break;

					case "dump":
						if (parts.length != 2) {
							System.out.println("Uso: dump <pid>");
							break;
						}
						try {
							int pid = Integer.parseInt(parts[1]);
							so.gp.dumpProcess(pid);
						} catch (NumberFormatException e) {
							System.out.println("PID deve ser um numero inteiro.");
						}
						break;

					case "dumpm":
						if (parts.length != 2) {
							System.out.println("Uso: dumpm <inicio>,<fim>");
							break;
						}
						String[] range = parts[1].split(",");
						if (range.length != 2) {
							System.out.println("Formato invalido. Use: dumpm <inicio>,<fim>");
							break;
						}
						try {
							int inicio = Integer.parseInt(range[0]);
							int fim = Integer.parseInt(range[1]);
							so.utils.dump(inicio, fim);
						} catch (NumberFormatException e) {
							System.out.println("Inicio e fim devem ser numeros inteiros.");
						}
						break;

					case "exec":
						if (parts.length != 2) {
							System.out.println("Uso: exec <pid>");
							break;
						}
						try {
							int pid = Integer.parseInt(parts[1]);
							so.gp.exec(pid);
						} catch (NumberFormatException e) {
							System.out.println("PID deve ser um numero inteiro.");
						}
						break;

					case "execAll":
						so.gp.execAll();
						break;

					case "traceon":
						hw.cpu.setDebug(true);
						break;

					case "traceoff":
						hw.cpu.setDebug(false);
						break;

					case "meminfo":
						System.out.println("Memoria: " + so.mm.getFreeFrameCount() + "/" + so.mm.getTotalFrames()
								+ " frames livres (tamanho frame: " + so.mm.getFrameSize() + " palavras)");
						break;

					case "help":
						System.out.println("Comandos disponiveis:");
						System.out.println("  new <nomePrograma>   - Cria um novo processo a partir de um programa conhecido");
						System.out.println("                         (Programas: " + progs.getAvailableProgramNames() + ")");
						System.out.println("  rm <pid>             - Remove o processo com o ID especificado");
						System.out.println("  ps                   - Lista todos os processos (running/ready)");
						System.out.println("  dump <pid>           - Mostra detalhes do PCB e memoria do processo");
						System.out.println("  dumpm <inicio>,<fim> - Mostra conteudo da memoria fisica no intervalo");
						System.out.println("  exec <pid>           - Executa o processo (apto) com o ID especificado");
						System.out.println("  execAll              - Executa todos os processos prontos");
						System.out.println("  traceon              - Ativa modo de debug da CPU (mostra cada instrucao)");
						System.out.println("  traceoff             - Desativa modo de debug da CPU");
						System.out.println("  meminfo              - Mostra status da memoria (frames livres/totais)");
						System.out.println("  exit                 - Encerra o simulador");
						System.out.println("  help                 - Mostra esta ajuda");
						break;

					case "exit":
						System.out.println("Encerrando o simulador...");
						return;

					default:
						System.out.println("Comando desconhecido: '" + command + "'. Digite 'help' para ajuda.");
						break;
				}
			} catch (Exception e) {
				System.err.println("!!! Erro inesperado processando comando: " + e.getMessage());
				e.printStackTrace();
			}
		}
	} // End run()

	// --- Main Method ---
	public static void main(String args[]) {
		int memorySize = 1024;
		int pageSize = 16;

		SistemaEscalonador s = new SistemaEscalonador(memorySize, pageSize);
		s.runV2();
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
		// Array holding the predefined programs
		public Program[] progs = {
				new Program("fatorial",
						new Word[] {
								// este fatorial so aceita valores positivos. nao pode ser zero
								// linha coment
								new Word(Opcode.LDI, 0, -1, 7), // 0 r0 é valor a calcular fatorial
								new Word(Opcode.LDI, 1, -1, 1), // 1 r1 é 1 para multiplicar (por r0)
								new Word(Opcode.LDI, 6, -1, 1), // 2 r6 é 1 o decremento
								new Word(Opcode.LDI, 7, -1, 8), // 3 r7 tem posicao 8 para fim do programa
								new Word(Opcode.JMPIE, 7, 0, 0), // 4 se r0=0 pula para r7(=8)
								new Word(Opcode.MULT, 1, 0, -1), // 5 r1 = r1 * r0 (r1 acumula o produto por cada termo)
								new Word(Opcode.SUB, 0, 6, -1), // 6 r0 = r0 - r6 (r6=1) decrementa r0 para proximo
																// termo
								new Word(Opcode.JMP, -1, -1, 4), // 7 vai p posicao 4
								new Word(Opcode.STD, 1, -1, 10), // 8 coloca valor de r1 na posição 10
								new Word(Opcode.STOP, -1, -1, -1), // 9 stop
								new Word(Opcode.DATA, -1, -1, -1) // 10 ao final o valor está na posição 10 da memória
						}),

				new Program("fatorialV2",
						new Word[] {
								new Word(Opcode.LDI, 0, -1, 5), // numero para colocar na memoria, ou pode ser lido
								new Word(Opcode.STD, 0, -1, 19),
								new Word(Opcode.LDD, 0, -1, 19),
								new Word(Opcode.LDI, 1, -1, -1),
								new Word(Opcode.LDI, 2, -1, 13), // SALVAR POS STOP
								new Word(Opcode.JMPIL, 2, 0, -1), // caso negativo pula pro STD
								new Word(Opcode.LDI, 1, -1, 1),
								new Word(Opcode.LDI, 6, -1, 1),
								new Word(Opcode.LDI, 7, -1, 13),
								new Word(Opcode.JMPIE, 7, 0, 0), // POS 9 pula para STD (Stop-1)
								new Word(Opcode.MULT, 1, 0, -1),
								new Word(Opcode.SUB, 0, 6, -1),
								new Word(Opcode.JMP, -1, -1, 9), // pula para o JMPIE
								new Word(Opcode.STD, 1, -1, 18),
								new Word(Opcode.LDI, 8, -1, 2), // escrita
								new Word(Opcode.LDI, 9, -1, 18), // endereco com valor a escrever
								new Word(Opcode.SYSCALL, -1, -1, -1),
								new Word(Opcode.STOP, -1, -1, -1), // POS 17
								new Word(Opcode.DATA, -1, -1, -1), // POS 18
								new Word(Opcode.DATA, -1, -1, -1) } // POS 19
				),
				new Program("inputTest",
						new Word[] {
								new Word(Opcode.LDI, 8, -1, 1), // R8 = 1 (código para input)
								new Word(Opcode.LDI, 9, -1, 10), // R9 = 10 (endereço para input)
								new Word(Opcode.SYSCALL, -1, -1, -1), // SYSCALL IN
								new Word(Opcode.LDI, 8, -1, 2), // R8 = 2 (código para output)
								new Word(Opcode.LDI, 9, -1, 10), // R9 = 10 (endereço para output)
								new Word(Opcode.SYSCALL, -1, -1, -1), // SYSCALL OUT
								new Word(Opcode.STOP, -1, -1, -1), // STOP
								new Word(Opcode.DATA, -1, -1, -1), // 10: espaço para input/output
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
						}),

				new Program("progMinimo",
						new Word[] {
								new Word(Opcode.LDI, 0, -1, 999),
								new Word(Opcode.STD, 0, -1, 8),
								new Word(Opcode.STD, 0, -1, 9),
								new Word(Opcode.STD, 0, -1, 10),
								new Word(Opcode.STD, 0, -1, 11),
								new Word(Opcode.STD, 0, -1, 12),
								new Word(Opcode.STOP, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1), // 7
								new Word(Opcode.DATA, -1, -1, -1), // 8
								new Word(Opcode.DATA, -1, -1, -1), // 9
								new Word(Opcode.DATA, -1, -1, -1), // 10
								new Word(Opcode.DATA, -1, -1, -1), // 11
								new Word(Opcode.DATA, -1, -1, -1), // 12
								new Word(Opcode.DATA, -1, -1, -1) // 13
						}),

				new Program("fibonacci10",
						new Word[] { // mesmo que prog exemplo, so que usa r0 no lugar de r8
								new Word(Opcode.LDI, 1, -1, 0),
								new Word(Opcode.STD, 1, -1, 20),
								new Word(Opcode.LDI, 2, -1, 1),
								new Word(Opcode.STD, 2, -1, 21),
								new Word(Opcode.LDI, 0, -1, 22),
								new Word(Opcode.LDI, 6, -1, 6),
								new Word(Opcode.LDI, 7, -1, 31),
								new Word(Opcode.LDI, 3, -1, 0),
								new Word(Opcode.ADD, 3, 1, -1),
								new Word(Opcode.LDI, 1, -1, 0),
								new Word(Opcode.ADD, 1, 2, -1),
								new Word(Opcode.ADD, 2, 3, -1),
								new Word(Opcode.STX, 0, 2, -1),
								new Word(Opcode.ADDI, 0, -1, 1),
								new Word(Opcode.SUB, 7, 0, -1),
								new Word(Opcode.JMPIG, 6, 7, -1),
								new Word(Opcode.STOP, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1), // POS 20
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1) // ate aqui - serie de fibonacci ficara armazenada
						}),
				// ... (Other programs remain the same) ...
				new Program("PC",
						new Word[] {
								// Para um N definido (10 por exemplo)
								// o programa ordena um vetor de N números em alguma posição de memória;
								// ordena usando bubble sort
								// loop ate que não swap nada
								// passando pelos N valores
								// faz swap de vizinhos se da esquerda maior que da direita
								new Word(Opcode.LDI, 7, -1, 5), // TAMANHO DO BUBBLE SORT (N)
								new Word(Opcode.LDI, 6, -1, 5), // aux N
								new Word(Opcode.LDI, 5, -1, 46), // LOCAL DA MEMORIA
								new Word(Opcode.LDI, 4, -1, 47), // aux local memoria
								new Word(Opcode.LDI, 0, -1, 4), // colocando valores na memoria
								new Word(Opcode.STD, 0, -1, 46),
								new Word(Opcode.LDI, 0, -1, 3),
								new Word(Opcode.STD, 0, -1, 47),
								new Word(Opcode.LDI, 0, -1, 5),
								new Word(Opcode.STD, 0, -1, 48),
								new Word(Opcode.LDI, 0, -1, 1),
								new Word(Opcode.STD, 0, -1, 49),
								new Word(Opcode.LDI, 0, -1, 2),
								new Word(Opcode.STD, 0, -1, 50), // colocando valores na memoria até aqui - POS 13
								new Word(Opcode.LDI, 3, -1, 25), // Posicao para pulo CHAVE 1 -> JMPILM target needs to
																	// be
																	// logical addr 25
								new Word(Opcode.STD, 3, -1, 99), // Storing jump target addr 25 at logical addr 99
								new Word(Opcode.LDI, 3, -1, 22), // Posicao para pulo CHAVE 2 -> JMPIGM target needs to
																	// be
																	// logical addr 22
								new Word(Opcode.STD, 3, -1, 98), // Storing jump target addr 22 at logical addr 98
								new Word(Opcode.LDI, 3, -1, 45), // Posicao para pulo CHAVE 3 -> JMPIEM target needs to
																	// be
																	// logical addr 45 (STOP)
								new Word(Opcode.STD, 3, -1, 97), // Storing jump target addr 45 at logical addr 97
								new Word(Opcode.LDI, 3, -1, 25), // Posicao para pulo CHAVE 4 -> JMPIGM target needs to
																	// be
																	// logical addr 25
								new Word(Opcode.STD, 3, -1, 96), // Storing jump target addr 25 at logical addr 96
								new Word(Opcode.LDI, 6, -1, 0), // r6 = r7 - 1 POS 22
								new Word(Opcode.ADD, 6, 7, -1),
								new Word(Opcode.SUBI, 6, -1, 1), // ate aqui
								// JMPIEM jumps to address stored at logical address P (which is 97) if R6==0
								new Word(Opcode.JMPIEM, -1, 6, 97), // Jumps to M[97] (should be 45=STOP) if R6==0
								// LDX R0, R5 <- Loads from M[Logical Addr in R5] into R0. POS 26
								new Word(Opcode.LDX, 0, 5, -1),
								new Word(Opcode.LDX, 1, 4, -1), // LDX R1, R4 <- Loads from M[Logical Addr in R4] into
																// R1
								new Word(Opcode.LDI, 2, -1, 0), // R2 = R0 - R1
								new Word(Opcode.ADD, 2, 0, -1),
								new Word(Opcode.SUB, 2, 1, -1), // Calculate difference R0-R1 into R2
								new Word(Opcode.ADDI, 4, -1, 1), // Increment R4 (inner loop address pointer)
								new Word(Opcode.SUBI, 6, -1, 1), // Decrement R6 (inner loop counter)
								// JMPILM jumps to address stored at P (99) if R2 < 0 (no swap needed)
								new Word(Opcode.JMPILM, -1, 2, 99), // Jump to M[99] (should be 25) if R2 < 0
								new Word(Opcode.STX, 5, 1, -1), // SWAP: Store R1 (original M[R4]) into M[R5]
								new Word(Opcode.SUBI, 4, -1, 1), // Decrement R4 temporarily to get original address
								new Word(Opcode.STX, 4, 0, -1), // SWAP: Store R0 (original M[R5]) into M[R4]
								new Word(Opcode.ADDI, 4, -1, 1), // Increment R4 back
								// JMPIGM jumps to address stored at P (96) if R6 > 0 (continue inner loop)
								new Word(Opcode.JMPIGM, -1, 6, 96), // Jump to M[96] (should be 25) if R6 > 0
								// --- End of Inner Loop --- POS 39
								new Word(Opcode.ADDI, 5, -1, 1), // Increment R5 (outer loop base address)
								new Word(Opcode.SUBI, 7, -1, 1), // Decrement R7 (outer loop counter)
								new Word(Opcode.LDI, 4, -1, 0), // r4 = r5 + 1 POS 41
								new Word(Opcode.ADD, 4, 5, -1),
								new Word(Opcode.ADDI, 4, -1, 1), // ate aqui -> R4 = R5 + 1
								// JMPIGM jumps to address stored at P (98) if R7 > 0 (continue outer loop)
								new Word(Opcode.JMPIGM, -1, 7, 98), // Jump to M[98] (should be 22) if R7 > 0
								// --- End of Outer Loop ---
								new Word(Opcode.STOP, -1, -1, -1), // POS 45
								// Data section - Bubble sort works on logical addresses 46-50
								new Word(Opcode.DATA, -1, -1, -1), // 46
								new Word(Opcode.DATA, -1, -1, -1), // 47
								new Word(Opcode.DATA, -1, -1, -1), // 48
								new Word(Opcode.DATA, -1, -1, -1), // 49
								new Word(Opcode.DATA, -1, -1, -1), // 50
								// Data section for jump targets
								new Word(Opcode.DATA, -1, -1, -1), // ... up to 95 are padding/unused
								new Word(Opcode.DATA, -1, -1, -1), // ...
								new Word(Opcode.DATA, -1, -1, -1), // ...
								new Word(Opcode.DATA, -1, -1, -1), // ...
								new Word(Opcode.DATA, -1, -1, -1), // 95
								new Word(Opcode.DATA, -1, -1, 25), // 96: Target for JMPIGM (inner loop cont)
								new Word(Opcode.DATA, -1, -1, 45), // 97: Target for JMPIEM (outer loop finish -> STOP)
								new Word(Opcode.DATA, -1, -1, 22), // 98: Target for JMPIGM (outer loop cont)
								new Word(Opcode.DATA, -1, -1, 25) // 99: Target for JMPILM (inner loop cont, no swap)
						})
		};

		public Word[] retrieveProgram(String pname) {
			if (pname == null)
				return null;
			for (Program p : progs) {
				if (p != null && pname.equals(p.name)) {
					return p.image;
				}
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
	} // --- Fim do Programs ---

	// NEW: Device class to handle I/O operations
	public class Device {
		private Queue<IORequest> requestQueue;
		private boolean isProcessing;
		private CPU cpu;
		private SO so;
		private Thread deviceThread;
		private RandomAccessFile inputFile; // NEW: File for program input
		private long lastPosition; // NEW: Track last read position

		public Device(CPU _cpu, SO _so) {
			this.cpu = _cpu;
			this.so = _so;
			this.requestQueue = new LinkedList<>();
			this.isProcessing = false;
			this.lastPosition = 0;
			try {
				this.inputFile = new RandomAccessFile("input.txt", "r");
			} catch (FileNotFoundException e) {
				System.err.println("Erro: Arquivo input.txt nao encontrado!");
				System.exit(1);
			}
			startDeviceThread();
		}

		private void startDeviceThread() {
			deviceThread = new Thread(() -> {
				while (true) {
					synchronized (requestQueue) {
						while (requestQueue.isEmpty()) {
							try {
								requestQueue.wait();
							} catch (InterruptedException e) {
								Thread.currentThread().interrupt();
								return;
							}
						}
						isProcessing = true;
						IORequest request = requestQueue.poll();
						processRequest(request);
						isProcessing = false;
					}
				}
			});
			deviceThread.setDaemon(true);
			deviceThread.start();
		}

		private void processRequest(IORequest request) {
			try {
				switch (request.operation) {
					case 1: // Input
						System.out.print("INPUT (para PID " + request.pid + "): ");
						// Read from file
						inputFile.seek(lastPosition);
						String inputLine = inputFile.readLine();
						if (inputLine == null) {
							System.err.println("    Erro: Fim do arquivo de input atingido.");
							cpu.irpt = Interrupts.intInstrucaoInvalida;
							break;
						}
						lastPosition = inputFile.getFilePointer();
						int inputValue = Integer.parseInt(inputLine);
						System.out.println(inputValue); // Echo the input

						// Translate address and store input
						int physicalAddress = cpu.translateAddress(request.address);
						if (cpu.irpt == Interrupts.noInterrupt && cpu.legal(physicalAddress)) {
							so.hw.mem.pos[physicalAddress].opc = Opcode.DATA;
							so.hw.mem.pos[physicalAddress].p = inputValue;
							System.out.println("    Input " + inputValue + " armazenado em M[" + physicalAddress
									+ "] (logico " + request.address + ")");
						} else {
							System.err.println("    Falha ao armazenar input: endereco invalido.");
							cpu.irpt = Interrupts.intEnderecoInvalido;
						}
						break;

					case 2: // Output
						System.out.println("OUTPUT (PID " + request.pid + "): " + request.value);
						break;

					default:
						System.err.println("    Operacao de I/O desconhecida: " + request.operation);
						break;
				}

				// Simulate I/O delay
				Thread.sleep(1000);

				// Signal I/O completion through interrupt
				cpu.irpt = Interrupts.intIO;
				// Store the completed request for the interrupt handler
				so.ioCompletedRequest = request;

			} catch (NumberFormatException e) {
				System.err.println("    Erro de Input: valor nao inteiro digitado.");
				cpu.irpt = Interrupts.intInstrucaoInvalida;
			} catch (IOException e) {
				System.err.println("    Erro de I/O: " + e.getMessage());
				cpu.irpt = Interrupts.intInstrucaoInvalida;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}

		public void addRequest(IORequest request) {
			synchronized (requestQueue) {
				requestQueue.add(request);
				requestQueue.notify();
			}
		}

		public boolean isProcessing() {
			return isProcessing;
		}
	}

	// NEW: Class to represent I/O requests
	public class IORequest {
		public int pid;
		public int operation; // 1 for input, 2 for output
		public int address; // Memory address for the operation
		public int value; // For output operations

		public IORequest(int pid, int operation, int address, int value) {
			this.pid = pid;
			this.operation = operation;
			this.address = address;
			this.value = value;
		}
	}

} // --- Fim da classe Sistema ---