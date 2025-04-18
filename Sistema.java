import java.util.*;
import java.util.concurrent.atomic.AtomicInteger; // For thread-safe PID generation if needed, otherwise simple int is fine for this model
import java.lang.Math; // For Math.ceil

public class Sistema {

	// ... (Memory, Word, Opcode, Interrupts remain the same) ...
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
		public Opcode opc; //
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
		noInterrupt, intEnderecoInvalido, intInstrucaoInvalida, intOverflow, intSTOP;
	}

	public class CPU {
		private int maxInt;
		private int minInt;

		private int pc;
		private Word ir;
		private int[] reg;
		private Interrupts irpt;

		private Word[] m; // Physical memory reference
		private List<Integer> page_table; // Current process's page table

		private int pageSize;

		private InterruptHandling ih;
		private SysCallHandling sysCall;

		private boolean cpuStop; // Control flag for stopping execution

		private boolean debug; // Trace flag
		private Utilities u;

		public CPU(Memory _mem, boolean _debug, int _pageSize) {
			maxInt = 32767;
			minInt = -32767;
			m = _mem.pos;
			reg = new int[10]; // Initialize registers
			debug = _debug;
			pageSize = _pageSize;
			pc = 0; // Initialize PC
			irpt = Interrupts.noInterrupt;
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
				System.err.println(">>> ERRO: Endereco fisico invalido: " + e);
				return false;
			}
		}

		private boolean testOverflow(int v) {
			if ((v < minInt) || (v > maxInt)) {
				irpt = Interrupts.intOverflow;
				System.err.println(">>> ERRO: Overflow com valor: " + v);
				return false;
			}
			return true;
		}

		// Set CPU context for a specific process
		public void setContext(int _pc, List<Integer> _page_table, int[] _regs) {
			pc = _pc;
			page_table = _page_table;
			// Copy registers to prevent aliasing issues if needed, but direct assignment is
			// simpler here
			System.arraycopy(_regs, 0, this.reg, 0, _regs.length);
			irpt = Interrupts.noInterrupt; // Reset interrupt flag for new execution context
			cpuStop = false; // Ensure CPU is ready to run
		}

		// Translate logical address to physical address using the current page table
		private int translateAddress(int logicalAddress) {
			if (page_table == null) {
				System.err.println(">>> ERRO: Tentativa de traducao sem tabela de paginas carregada!");
				irpt = Interrupts.intEnderecoInvalido;
				return -1;
			}
			int pageNumber = logicalAddress / pageSize;
			int offset = logicalAddress % pageSize;

			// Check if page number is valid within the table
			if (pageNumber < 0 || pageNumber >= page_table.size() || page_table.get(pageNumber) == null) {
				System.err.println(">>> ERRO: Endereco logico " + logicalAddress + " (pagina " + pageNumber
						+ ") fora dos limites da tabela ou pagina nao mapeada.");
				irpt = Interrupts.intEnderecoInvalido;
				return -1;
			}

			int frameNumber = page_table.get(pageNumber);
			int physicalAddress = (frameNumber * pageSize) + offset;

			// Optional: Double check physical address range (should be handled by 'legal'
			// anyway)
			// if (physicalAddress < 0 || physicalAddress >= m.length) {
			// System.err.println(">>> ERRO: Endereco fisico calculado " + physicalAddress +
			// " fora dos limites da memoria.");
			// irpt = Interrupts.intEnderecoInvalido;
			// return -1;
			// }

			return physicalAddress;
		}

		// --- Getters for Context Saving ---
		public int getPC() {
			return pc;
		}

		public int[] getRegs() {
			// Return a copy to prevent external modification? Or return reference?
			// For this assignment, returning reference is likely acceptable.
			return reg;
			// return Arrays.copyOf(reg, reg.length); // Safer option
		}

		// --- Setter for Trace Flag ---
		public void setDebug(boolean _debug) {
			this.debug = _debug;
			System.out.println("CPU: Modo trace " + (this.debug ? "ativado." : "desativado."));
		}

		public void run() {
			cpuStop = false; // Reset stop flag before starting/resuming
			while (!cpuStop) {
				// 1. Fetch Instruction (translate PC)
				int physicalPC = translateAddress(pc);
				if (irpt != Interrupts.noInterrupt) { // Check if translation failed
					System.err.println("CPU: Falha ao traduzir PC logico " + pc);
					break; // Stop execution if PC translation fails
				}

				if (!legal(physicalPC)) { // Check if physical PC is valid
					System.err.println("CPU: PC Fisico Invalido: " + physicalPC);
					// irpt is set by legal()
					break; // Stop execution
				}

				ir = m[physicalPC]; // Fetch instruction word

				// --- Debug Output ---
				if (debug) {
					System.out.print("  CPU State: PC_log=" + pc + " PC_fis=" + physicalPC + " IR=[" + ir.opc + ","
							+ ir.ra + "," + ir.rb + "," + ir.p + "] ");
					System.out.print(" Regs=[");
					for (int i = 0; i < reg.length; i++) {
						System.out.print("r" + i + ":" + reg[i] + (i == reg.length - 1 ? "" : ","));
					}
					System.out.println("]");
				}

				// 2. Decode and Execute Instruction
				switch (ir.opc) {
					case LDI:
						reg[ir.ra] = ir.p;
						pc++;
						break;
					case LDD: {
						int logicalAddress = ir.p;
						int physicalAddress = translateAddress(logicalAddress);
						if (irpt == Interrupts.noInterrupt && legal(physicalAddress)) {
							if (m[physicalAddress].opc == Opcode.DATA) { // Ensure it's data
								reg[ir.ra] = m[physicalAddress].p;
								pc++;
							} else {
								System.err.println(">>> ERRO: LDD tentando ler de posicao de codigo em end logico "
										+ logicalAddress);
								irpt = Interrupts.intInstrucaoInvalida; // Or address invalid? Let's use instruction
																		// invalid
							}
						}
						break; // Break here, error handled by interrupt check later
					}
					case LDX: {
						int logicalAddress = reg[ir.rb]; // Address is in register rb
						int physicalAddress = translateAddress(logicalAddress);
						if (irpt == Interrupts.noInterrupt && legal(physicalAddress)) {
							if (m[physicalAddress].opc == Opcode.DATA) { // Ensure it's data
								reg[ir.ra] = m[physicalAddress].p;
								pc++;
							} else {
								System.err.println(">>> ERRO: LDX tentando ler de posicao de codigo em end logico "
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
							m[physicalAddress].opc = Opcode.DATA; // Mark as data
							m[physicalAddress].p = reg[ir.ra];
							pc++;
							if (debug) {
								System.out.print("        STD M[" + physicalAddress + "] <- " + reg[ir.ra] + " ");
								u.dump(m[physicalAddress]); // Dump the specific word changed
							}
						}
						break;
					}
					case STX: {
						int logicalAddress = reg[ir.rb]; // Address is in register rb
						int physicalAddress = translateAddress(logicalAddress);
						if (irpt == Interrupts.noInterrupt && legal(physicalAddress)) {
							m[physicalAddress].opc = Opcode.DATA; // Mark as data
							m[physicalAddress].p = reg[ir.ra]; // Value is in register ra
							pc++;
							if (debug) {
								System.out.print("        STX M[" + physicalAddress + "] <- " + reg[ir.ra] + " ");
								u.dump(m[physicalAddress]);
							}
						}
						break;
					}
					case MOVE: // Assuming MOVE Rx, Ry -> Rx = Ry
						reg[ir.ra] = reg[ir.rb];
						pc++;
						break;

					// Arithmetic
					case ADD:
						reg[ir.ra] = reg[ir.ra] + reg[ir.rb];
						testOverflow(reg[ir.ra]);
						pc++;
						break;
					case ADDI:
						reg[ir.ra] = reg[ir.ra] + ir.p;
						testOverflow(reg[ir.ra]);
						pc++;
						break;
					case SUB:
						reg[ir.ra] = reg[ir.ra] - reg[ir.rb];
						testOverflow(reg[ir.ra]);
						pc++;
						break;
					case SUBI:
						reg[ir.ra] = reg[ir.ra] - ir.p;
						testOverflow(reg[ir.ra]);
						pc++;
						break;
					case MULT:
						reg[ir.ra] = reg[ir.ra] * reg[ir.rb];
						testOverflow(reg[ir.ra]);
						pc++;
						break;

					// Jumps (update PC only, address translation happens at fetch)
					case JMP: // Jump to logical address P
						pc = ir.p;
						break;
					case JMPI: // Jump to logical address in Ra
						pc = reg[ir.ra];
						break;
					case JMPIG: // Jump to logical address in Ra if Rb > 0
						if (reg[ir.rb] > 0) {
							pc = reg[ir.ra];
						} else {
							pc++;
						}
						break;
					case JMPIL: // Jump to logical address in Ra if Rb < 0
						if (reg[ir.rb] < 0) {
							pc = reg[ir.ra];
						} else {
							pc++;
						}
						break;
					case JMPIE: // Jump to logical address in Ra if Rb == 0
						if (reg[ir.rb] == 0) {
							pc = reg[ir.ra];
						} else {
							pc++;
						}
						break;

					case JMPIM: { // Jump to logical address stored at logical address P
						int logicalAddress = ir.p;
						int physicalAddress = translateAddress(logicalAddress);
						if (irpt == Interrupts.noInterrupt && legal(physicalAddress)) {
							pc = m[physicalAddress].p; // The new PC is the value at M[physicalAddress]
						}
						break; // Error handled by interrupt check
					}
					case JMPIGM: { // Jump to logical address stored at logical address P if Rb > 0
						if (reg[ir.rb] > 0) {
							int logicalAddress = ir.p;
							int physicalAddress = translateAddress(logicalAddress);
							if (irpt == Interrupts.noInterrupt && legal(physicalAddress)) {
								pc = m[physicalAddress].p;
							}
							// If translation/legality fails, irpt is set, loop will check
						} else {
							pc++;
						}
						break;
					}
					case JMPILM: { // Jump to logical address stored at logical address P if Rb < 0
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
					case JMPIEM: { // Jump to logical address stored at logical address P if Rb == 0
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

					// Conditional jumps to constant logical address P
					case JMPIGK: // Jump to logical address P if Rb > 0
						if (reg[ir.rb] > 0) {
							pc = ir.p;
						} else {
							pc++;
						}
						break;
					case JMPILK: // Jump to logical address P if Rb < 0
						if (reg[ir.rb] < 0) {
							pc = ir.p;
						} else {
							pc++;
						}
						break;
					case JMPIEK: // Jump to logical address P if Rb == 0
						if (reg[ir.rb] == 0) {
							pc = ir.p;
						} else {
							pc++;
						}
						break;
					case JMPIGT: // Jump to logical address P if Ra > Rb
						if (reg[ir.ra] > reg[ir.rb]) {
							pc = ir.p;
						} else {
							pc++;
						}
						break;

					// System Calls & Stop
					case SYSCALL:
						sysCall.handle(); // Handle system call
						// Assuming syscall might modify state but doesn't necessarily stop CPU
						pc++; // Increment PC after syscall completes
						// Note: If syscall needs to block/yield, much more logic is needed
						break;

					case STOP:
						irpt = Interrupts.intSTOP; // Use interrupt mechanism to signal stop
						cpuStop = true; // Set flag to stop CPU loop
						// Don't increment PC after stop
						break;

					// Data / Invalid Opcodes
					case DATA:
					case ___:
					default:
						irpt = Interrupts.intInstrucaoInvalida;
						System.err.println(">>> ERRO: Opcode invalido encontrado: " + ir.opc + " em PC logico " + (pc));
						break;
				}

				// 3. Check for Interrupts (generated during execution)
				if (irpt != Interrupts.noInterrupt) {
					ih.handle(irpt); // Call interrupt handler
					cpuStop = true; // Stop CPU execution cycle on interrupt
				}
			} // End of while(!cpuStop) loop
				// System.out.println("CPU: Ciclo de execução terminado."); // Debug message
		} // End of run()
	}

	// ... (HW class remains the same) ...
	public class HW {
		public Memory mem;
		public CPU cpu;
		public int pageSize;

		public HW(int tamMem, int _pageSize) {
			mem = new Memory(tamMem);
			pageSize = _pageSize;
			cpu = new CPU(mem, false, pageSize); // Start with trace OFF by default
		}
	}

	// ... (InterruptHandling class remains the same) ...
	public class InterruptHandling {
		// private HW hw; // Not strictly needed if only printing PC from CPU state at
		// time of interrupt
		private CPU cpu; // Need CPU reference to get PC at time of interrupt

		public InterruptHandling(HW _hw) {
			// hw = _hw;
			cpu = _hw.cpu;
		}

		public void handle(Interrupts irpt) {
			System.out.println("-----------------------------------------------------");
			System.out.println(">>> INTERRUPCAO: " + irpt + " ocorrida em PC logico: " + cpu.getPC());
			System.out.println("-----------------------------------------------------");
			// In a real system, this would trigger context switch or process termination
			// logic
		}
	}

	// ... (SysCallHandling - Minimal changes needed for now) ...
	public class SysCallHandling {
		private HW hw;
		private CPU cpu; // Need CPU to access registers
		private Utilities utils; // May need utils for output formatting or memory access if needed
		private Memory mem; // Need Memory reference

		public SysCallHandling(HW _hw, Utilities _utils) { // Pass utils if needed
			hw = _hw;
			cpu = hw.cpu;
			utils = _utils;
			mem = hw.mem;
		}

		// Called by CPU when STOP is encountered
		public void stop() { // This method name is a bit confusing as it handles the STOP *instruction*
			// The actual 'stop' action is handled via interrupt mechanism now
			// System.out.println(" SYSCALL STOP (handled via interrupt)");
		}

		// Called by CPU on SYSCALL instruction
		public void handle() {
			int operation = cpu.getRegs()[8]; // Syscall code in R8
			int arg = cpu.getRegs()[9]; // Argument in R9 (often an address)

			System.out.print(">>> SYSCALL: Operation=" + operation);

			switch (operation) {
				case 1: // Input (example, not fully implemented)
					System.out.println(" (Input Request)");
					// Needs interaction with external input, translate address in R9, store result
					// Example: Read integer and store at logical address in R9
					try {
						Scanner sc = new Scanner(System.in);
						System.out.print("INPUT (para R9=" + arg + "): ");
						int inputValue = sc.nextInt();
						int physicalAddress = cpu.translateAddress(arg);
						if (cpu.irpt == Interrupts.noInterrupt && cpu.legal(physicalAddress)) {
							mem.pos[physicalAddress].opc = Opcode.DATA;
							mem.pos[physicalAddress].p = inputValue;
							System.out.println("    Input " + inputValue + " armazenado em M[" + physicalAddress
									+ "] (logico " + arg + ")");
						} else {
							System.err.println("    Falha ao armazenar input: endereco invalido.");
							// Interrupt should have been set by translate or legal
						}
					} catch (InputMismatchException e) {
						System.err.println("    Erro de Input: valor nao inteiro digitado.");
						// Decide how to handle - set interrupt? crash process?
						cpu.irpt = Interrupts.intInstrucaoInvalida; // Treat as error for now
					}
					break;
				case 2: // Output
					System.out.print(" (Output Request from logical addr R9=" + arg + ")");
					int physicalAddressOut = cpu.translateAddress(arg);
					if (cpu.irpt == Interrupts.noInterrupt && cpu.legal(physicalAddressOut)) {
						System.out.println("\nOUTPUT: " + mem.pos[physicalAddressOut].p);
					} else {
						System.err.println("\n    Falha no Output: endereco invalido.");
						// Interrupt should have been set by translate or legal
					}
					break;
				default:
					System.out.println(" (Codigo de Operacao Invalido: " + operation + ")");
					// Set interrupt for invalid syscall?
					cpu.irpt = Interrupts.intInstrucaoInvalida;
					break;
			}
		}
	}

	public class Utilities {
		private HW hw;
		private SO so; // Need reference to SO to access managers if needed

		// Constructor updated to receive SO
		public Utilities(HW _hw, SO _so) {
			hw = _hw;
			so = _so; // Store SO reference
		}

		// Dump a single word - unchanged
		public void dump(Word w) {
			System.out.printf("[ %-7s %3d %3d %4d ]", w.opc, w.ra, w.rb, w.p); // Formatted output
		}

		// Dump a range of PHYSICAL memory - unchanged
		public void dump(int ini, int fim) {
			System.out.println("--- Dump da Memoria Fisica (Enderecos: " + ini + " a " + (fim - 1) + ") ---");
			Word[] m = hw.mem.pos;
			// Adjust fim to not exceed memory bounds
			int end = Math.min(fim, m.length);
			int start = Math.max(0, ini); // Ensure start is not negative

			for (int i = start; i < end; i++) {
				System.out.printf("%04d: ", i); // Padded address
				dump(m[i]);
				System.out.println(); // Newline after each word
			}
			System.out.println("--------------------------------------------------");
		}

		// Helper to dump memory allocated to a specific process using its page table
		// Moved here from thought process draft
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

					// Dump contents of the frame
					for (int addr = frameStart; addr < frameEnd; addr++) {
						// Calculate approximate logical address for context (optional)
						// int logicalAddr = pageIndex * pageSize + (addr - frameStart);
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

		// Load program words into specific physical memory locations based on page
		// table
		// Returns true on success, false on failure
		public boolean loadProgramToMemory(Word[] program, List<Integer> pageTable) {
			int programSize = program.length;
			int pageSize = hw.pageSize;
			Word[] memory = hw.mem.pos;

			System.out.println("UTILS: Carregando " + programSize + " palavras usando tabela: " + pageTable);

			for (int i = 0; i < programSize; i++) {
				int logicalAddress = i;
				int pageNumber = logicalAddress / pageSize;
				int offset = logicalAddress % pageSize;

				// Check if page table is large enough
				if (pageNumber >= pageTable.size() || pageTable.get(pageNumber) == null) {
					System.err.println("UTILS: Erro ao carregar - Tabela de paginas nao tem entrada para pagina logica "
							+ pageNumber);
					return false; // Loading failed
				}

				int frameNumber = pageTable.get(pageNumber);
				int physicalAddress = (frameNumber * pageSize) + offset;

				// Check if physical address is valid
				if (physicalAddress >= 0 && physicalAddress < memory.length) {
					memory[physicalAddress].opc = program[i].opc;
					memory[physicalAddress].ra = program[i].ra;
					memory[physicalAddress].rb = program[i].rb;
					memory[physicalAddress].p = program[i].p;
					// Optional debug print during load
					// if(hw.cpu.debug) System.out.println(" Loaded word "+i+" to physical addr
					// "+physicalAddress);
				} else {
					System.err.println("UTILS: Erro ao carregar - Endereco fisico calculado invalido: "
							+ physicalAddress + " para endereco logico " + logicalAddress);
					// This implies an issue with MemoryManager or page table content
					return false; // Loading failed
				}
			}
			System.out.println("UTILS: Programa carregado com sucesso.");
			return true; // Loading successful
		}

		// The old loadAndExec is removed as process creation and execution are now
		// separate
		// private void loadAndExec(Word[] p) { ... }
	}

	// Contexto remains the same
	public class Contexto {
		public int[] regs;
		public int pc;
		// Removed pid from here, it's part of PCB

		public Contexto() { // Initialize context
			this.pc = 0;
			this.regs = new int[10]; // Assuming 10 registers R0-R9
			Arrays.fill(this.regs, 0); // Initialize registers to 0
		}

		// This method isn't really needed if we access fields directly or copy from CPU
		// public void set_state(int[] regs, int pc) {
		// this.regs = regs; // Be careful with array references
		// this.pc = pc;
		// }
	}

	// ProcessControlBlock remains largely the same
	public class ProcessControlBlock {
		public int pid;
		public List<Integer> pageTable;
		// public boolean isRunning; // State derived from being 'running' variable or
		// in 'aptos' list
		// public ProcessControlBlock next; // Removing linked list aspect
		public Contexto contexto;
		public String programName; // Added to store the program name for 'ps'

		public ProcessControlBlock(int pid, List<Integer> pageTable, String programName) {
			this.pid = pid;
			this.pageTable = pageTable; // Reference to the page table list
			this.programName = programName;
			// this.isRunning = false;
			// this.next = null;
			this.contexto = new Contexto(); // Create a new context (PC=0, regs initialized)
		}
	}

	// --- Gerenciador de Processos (ProcessManagement) - Updated ---
	public class ProcessManagement {

		private List<ProcessControlBlock> aptos; // Ready queue
		private ProcessControlBlock running; // Currently running process PCB
		private CPU cpu;
		private MemoryManagment mm; // Need reference to Memory Manager
		private Utilities utils; // Need reference to Utilities
		private HW hw; // Need reference to HW for pageSize etc.

		private static AtomicInteger nextPid = new AtomicInteger(0); // Unique PID generator

		public ProcessManagement(CPU _cpu, MemoryManagment _mm, Utilities _utils, HW _hw) {
			this.aptos = new LinkedList<>(); // Use LinkedList for efficient add/remove (like a queue)
			this.running = null;
			this.cpu = _cpu;
			this.mm = _mm;
			this.utils = _utils;
			this.hw = _hw;
		}

		// 1. Create Process
		// boolean criaProcesso( programa )
		public boolean criaProcesso(Word[] programa, String programName) {
			if (programa == null || programa.length == 0) {
				System.out.println("GP: Erro - Programa invalido ou vazio.");
				return false;
			}

			int programSize = programa.length;
			System.out
					.println("GP: Tentando criar processo para '" + programName + "' (" + programSize + " palavras).");

			// Ask Memory Manager for allocation
			List<Integer> pageTable = new ArrayList<>(); // Create a new list for this process's page table
			if (!mm.aloca(programSize, pageTable)) {
				System.out.println("GP: Falha ao criar processo - Memoria insuficiente.");
				// mm.aloca should print its own error message
				return false; // Allocation failed
			}
			System.out.println("GP: Memoria alocada com sucesso. Tabela de Paginas: " + pageTable);

			// Create PCB
			int pid = nextPid.getAndIncrement(); // Get unique PID
			ProcessControlBlock newPCB = new ProcessControlBlock(pid, pageTable, programName);
			// PC=0 and registers are initialized in Contexto constructor

			// Load program into allocated memory using the page table
			if (!utils.loadProgramToMemory(programa, pageTable)) {
				System.err.println(
						"GP: Falha ao carregar o programa na memoria para PID " + pid + ". Desalocando memoria.");
				mm.desaloca(pageTable); // Clean up allocated memory
				// Don't increment PID again if creation fails here? Maybe revert PID counter?
				// For now, PID is consumed.
				return false;
			}
			System.out.println("GP: Programa carregado na memoria para PID " + pid);

			// Add PCB to the ready queue
			aptos.add(newPCB);
			System.out.println("GP: Processo '" + programName + "' criado com sucesso. PID: " + pid
					+ ". Adicionado a fila de aptos.");

			return true;
		}

		// 2. Deallocate Process
		// desalocaProcesso (id)
		public void desalocaProcesso(int pid) {
			System.out.println("GP: Tentando desalocar processo PID: " + pid);
			// Check if it's the running process
			if (running != null && running.pid == pid) {
				System.out.println("GP: Desalocando processo em execucao (PID: " + pid + ").");
				mm.desaloca(running.pageTable); // Deallocate memory
				running = null; // Set running to null
				// Note: CPU context is lost. If preemptive multitasking existed, context saving
				// would be crucial here.
				System.out.println("GP: Processo " + pid + " (em execucao) desalocado.");
				return;
			}

			// Search in the ready queue (aptos)
			Iterator<ProcessControlBlock> iterator = aptos.iterator();
			while (iterator.hasNext()) {
				ProcessControlBlock pcb = iterator.next();
				if (pcb.pid == pid) {
					System.out.println("GP: Desalocando processo na fila de aptos (PID: " + pid + ").");
					iterator.remove(); // Remove from ready queue
					mm.desaloca(pcb.pageTable); // Deallocate memory
					System.out.println("GP: Processo " + pid + " (apto) desalocado.");
					return;
				}
			}

			// If not found running or ready
			System.out.println("GP: Processo PID " + pid + " nao encontrado para desalocacao.");
		}

		// Execute Process (simple version: run until stop/interrupt)
		// exec <id>
		public void exec(int pid) {
			if (running != null) {
				System.out.println("GP: Erro - Ja existe um processo em execucao (PID: " + running.pid + "). Use 'rm "
						+ running.pid + "' primeiro se necessario.");
				return;
			}

			// Find the process in the ready queue
			ProcessControlBlock pcbToRun = null;
			Iterator<ProcessControlBlock> iterator = aptos.iterator();
			while (iterator.hasNext()) {
				ProcessControlBlock pcb = iterator.next();
				if (pcb.pid == pid) {
					pcbToRun = pcb;
					iterator.remove(); // Remove from ready queue
					break;
				}
			}

			if (pcbToRun != null) {
				System.out.println("-----------------------------------------------------");
				System.out.println(
						"GP: Iniciando execucao do processo PID: " + pid + " ('" + pcbToRun.programName + "')");
				running = pcbToRun; // Set as the running process

				// Set CPU context from PCB
				cpu.setContext(running.contexto.pc, running.pageTable, running.contexto.regs);

				// Run the CPU
				cpu.run(); // Executes instructions until cpuStop is true (STOP, interrupt, error)

				// After CPU stops (for whatever reason)
				System.out.println("GP: Execucao do processo PID: " + pid + " terminada.");

				// Save the final context back to the PCB (important!)
				running.contexto.pc = cpu.getPC();
				// Copy registers back - be careful if getRegs returns a reference vs copy
				System.arraycopy(cpu.getRegs(), 0, running.contexto.regs, 0, running.contexto.regs.length);

				// Decide what to do with the process now.
				// Simple model: Assume it finished or hit an error. It's no longer running.
				// It doesn't automatically go back to ready queue. Requires 'rm' or another
				// 'exec'.
				// (A real scheduler would handle state transitions like Ready -> Running ->
				// Blocked -> Ready etc.)
				System.out.println("GP: Contexto final salvo para PID " + pid + " (PC=" + running.contexto.pc + ")");
				// Add process back to ready queue? Optional based on desired behavior.
				// aptos.add(running); // Uncomment if process should return to ready after
				// running once

				running = null; // Set running process to null
				System.out.println("-----------------------------------------------------");

			} else {
				System.out.println("GP: Processo PID " + pid + " nao encontrado na fila de aptos para execucao.");
			}
		}

		// List Processes
		// ps
		public void listProcesses() {
			System.out.println("--- Lista de Processos Ativos ---");
			boolean found = false;
			if (running != null) {
				System.out.println("  PID: " + running.pid + "\t Nome: '" + running.programName
						+ "' \t Estado: Running \t PC: " + running.contexto.pc);
				found = true;
			}
			if (!aptos.isEmpty()) {
				System.out.println("--- Fila de Aptos ---");
				for (ProcessControlBlock pcb : aptos) {
					System.out.println("  PID: " + pcb.pid + "\t Nome: '" + pcb.programName
							+ "' \t Estado: Ready \t PC: " + pcb.contexto.pc);
				}
				found = true;
			}

			if (!found) {
				System.out.println("  Nenhum processo ativo no sistema.");
			}
			System.out.println("---------------------------------");
		}

		// Dump Process Info
		// dump <id>
		public void dumpProcess(int pid) {
			ProcessControlBlock pcbToDump = null;

			// Check if it's the running process
			if (running != null && running.pid == pid) {
				pcbToDump = running;
				System.out.println("--- Dump do Processo (Running) PID: " + pid + " ---");
			} else {
				// Search in the ready queue
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

				// Dump the actual memory content for this process
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

		// Helper to find a PCB (used internally or could be public)
		public ProcessControlBlock findPCB(int pid) {
			if (running != null && running.pid == pid) {
				return running;
			}
			for (ProcessControlBlock pcb : aptos) {
				if (pcb.pid == pid) {
					return pcb;
				}
			}
			return null;
		}

	} // --- Fim do ProcessManagement ---

	// --- MemoryManagment (Gerenciador de Memoria) - Updated slightly for clarity
	// ---
	public class MemoryManagment {
		private Set<Integer> freeFrames; // Use Set for efficient add/remove/check
		private int frameSize; // Tamanho do frame (pagina)
		private int totalFrames; // Numero total de frames na memoria
		private int memSize; // Tamanho total da memoria fisica

		public MemoryManagment(int tamMem, int tamFrame) {
			this.memSize = tamMem;
			this.frameSize = tamFrame;
			this.totalFrames = tamMem / tamFrame;
			this.freeFrames = new HashSet<>();

			System.out.println("GM: Inicializando com " + totalFrames + " frames de tamanho " + tamFrame
					+ " (Total Mem: " + tamMem + ")");
			// Initialize all frames as free
			for (int i = 0; i < totalFrames; i++) {
				freeFrames.add(i); // Store frame numbers (0 to N-1)
			}
			System.out.println("GM: Frames livres iniciais: " + freeFrames.size());
		}

		// Aloca memoria para um processo
		// Retorna true se sucesso, false se falha. Popula a pageTable fornecida.
		public boolean aloca(int numPalavras, List<Integer> pageTable) {
			int numFramesNeeded = (int) Math.ceil((double) numPalavras / frameSize);
			System.out.println("GM: Pedido de alocacao para " + numPalavras + " palavras (" + numFramesNeeded
					+ " frames). Frames livres: " + freeFrames.size());

			if (freeFrames.size() < numFramesNeeded) {
				System.out.println("GM: Erro - Nao ha frames livres suficientes.");
				return false; // Not enough free frames
			}

			// Get an iterator for the set of free frames
			Iterator<Integer> iterator = freeFrames.iterator();
			List<Integer> allocatedFrames = new ArrayList<>(); // Keep track of frames allocated in this call

			// Allocate the required number of frames
			for (int i = 0; i < numFramesNeeded; i++) {
				if (iterator.hasNext()) {
					int frameNumber = iterator.next();
					allocatedFrames.add(frameNumber); // Add to our list for this allocation
					iterator.remove(); // Remove the frame from the free set *immediately*
				} else {
					// Should not happen if initial size check passed, but good practice
					System.err.println("GM: Erro inesperado - Faltaram frames durante a alocacao!");
					// Rollback: Add already allocated frames back to free set
					freeFrames.addAll(allocatedFrames);
					pageTable.clear(); // Clear the possibly partially filled table
					return false;
				}
			}

			// Populate the provided pageTable with the allocated frame numbers
			pageTable.addAll(allocatedFrames);

			System.out.println("GM: Alocacao bem-sucedida. Frames alocados: " + allocatedFrames
					+ ". Frames livres restantes: " + freeFrames.size());
			return true; // Allocation successful
		}

		// Desaloca memoria associada a uma tabela de paginas
		public void desaloca(List<Integer> pageTable) {
			if (pageTable == null || pageTable.isEmpty()) {
				System.out.println("GM: Aviso - Tentativa de desalocar com tabela de paginas vazia ou nula.");
				return;
			}
			System.out.print("GM: Desalocando frames da tabela: " + pageTable + ". Frames liberados: ");
			int count = 0;
			for (Integer frameNumber : pageTable) {
				if (frameNumber != null) { // Ensure frame number is valid
					if (freeFrames.add(frameNumber)) { // Add back to the free set
						System.out.print(frameNumber + " ");
						count++;
					} else {
						// This might happen if trying to free an already free frame - indicates a
						// potential logic error elsewhere
						System.err.print("(Warning: Frame " + frameNumber + " ja estava livre?) ");
					}
				}
			}
			System.out.println("\nGM: Total de " + count + " frames retornados ao conjunto livre. Frames livres agora: "
					+ freeFrames.size());
			pageTable.clear(); // Clear the original table after freeing
		}

		// Optional: Get free frame count
		public int getFreeFrameCount() {
			return freeFrames.size();
		}

	} // --- Fim do MemoryManagment ---

	// --- SO (Sistema Operacional) - Links the components ---
	public class SO {
		public InterruptHandling ih;
		public SysCallHandling sc;
		public Utilities utils;
		public MemoryManagment mm;
		public ProcessManagement gp;
		public HW hw; // Keep a reference to HW if needed by SO directly

		public SO(HW _hw) {
			this.hw = _hw; // Store HW reference
			// Create managers IN ORDER of dependency (Utilities might need SO later)
			mm = new MemoryManagment(hw.mem.getSize(), hw.pageSize);
			// Utilities needs HW, SO (pass 'this' carefully or set later)
			utils = new Utilities(hw, this); // Pass SO reference to Utilities
			// Handlers need HW (and potentially utils)
			ih = new InterruptHandling(hw);
			sc = new SysCallHandling(hw, utils); // Pass utils to SysCall handler
			// Process Manager needs CPU, MM, Utils, HW
			gp = new ProcessManagement(hw.cpu, mm, utils, hw);

			// Set references in CPU
			hw.cpu.setAddressOfHandlers(ih, sc);
			hw.cpu.setUtilities(utils); // Pass Utilities reference to CPU
			System.out.println("SO: Sistema Operacional inicializado.");
		}
	}

	// --- Sistema class members ---
	public HW hw;
	public SO so;
	public Programs progs; // To load programs by name

	// --- Sistema Constructor ---
	public Sistema(int tamMem, int page_size) {
		hw = new HW(tamMem, page_size);
		so = new SO(hw); // SO now initializes all its components
		// hw.cpu.setUtilities(so.utils); // This is now done inside SO constructor
		progs = new Programs(); // Load program definitions
		System.out.println("Sistema: Hardware e SO criados. Pronto para comandos.");
	}

	// --- Interactive Command Shell ---
	public void run() {
		Scanner scanner = new Scanner(System.in);
		System.out.println("\n--- Simulador de SO v2.0 ---");
		System.out.println("Digite 'help' para ver os comandos.");

		while (true) {
			System.out.print("\n> ");
			String line = scanner.nextLine().trim();
			if (line.isEmpty()) {
				continue;
			}

			String[] parts = line.split("\\s+", 2); // Split into command and the rest
			String command = parts[0].toLowerCase();
			String args = parts.length > 1 ? parts[1] : "";

			try { // Add try-catch for parsing errors
				switch (command) {
					case "new":
						if (args.isEmpty()) {
							System.out.println("Uso: new <nomeDoPrograma>");
							System.out.println("Programas disponiveis: " + progs.getAvailableProgramNames());
						} else {
							Word[] programImage = progs.retrieveProgram(args);
							if (programImage != null) {
								boolean success = so.gp.criaProcesso(programImage, args);
								// criaProcesso now prints success/failure messages including PID
							} else {
								System.out.println("Erro: Programa '" + args + "' nao encontrado.");
								System.out.println("Programas disponiveis: " + progs.getAvailableProgramNames());
							}
						}
						break;

					case "rm":
						if (args.isEmpty()) {
							System.out.println("Uso: rm <pid>");
						} else {
							try {
								int pid = Integer.parseInt(args);
								so.gp.desalocaProcesso(pid);
							} catch (NumberFormatException e) {
								System.out.println("Erro: PID invalido '" + args + "'. Deve ser um numero.");
							}
						}
						break;

					case "ps":
						so.gp.listProcesses();
						break;

					case "dump":
						if (args.isEmpty()) {
							System.out.println("Uso: dump <pid>");
						} else {
							try {
								int pid = Integer.parseInt(args);
								so.gp.dumpProcess(pid);
							} catch (NumberFormatException e) {
								System.out.println("Erro: PID invalido '" + args + "'. Deve ser um numero.");
							}
						}
						break;

					case "dumpm": // dumpM
						String[] memArgs = args.split("\\s*,\\s*|\\s+"); // Split by comma or space
						if (memArgs.length != 2) {
							System.out.println("Uso: dumpm <inicio>, <fim>  ou  dumpm <inicio> <fim>");
						} else {
							try {
								int start = Integer.parseInt(memArgs[0]);
								int end = Integer.parseInt(memArgs[1]);
								if (start < 0 || end <= start || end > hw.mem.getSize()) {
									System.out.println("Erro: Intervalo invalido [" + start + ", " + end
											+ "). Max memoria: " + hw.mem.getSize());
								} else {
									so.utils.dump(start, end); // Dump physical memory range
								}
							} catch (NumberFormatException e) {
								System.out.println("Erro: Inicio/Fim invalidos. Devem ser numeros.");
							}
						}
						break;

					case "exec":
						if (args.isEmpty()) {
							System.out.println("Uso: exec <pid>");
						} else {
							try {
								int pid = Integer.parseInt(args);
								so.gp.exec(pid);
							} catch (NumberFormatException e) {
								System.out.println("Erro: PID invalido '" + args + "'. Deve ser um numero.");
							}
						}
						break;

					case "traceon":
						hw.cpu.setDebug(true);
						break;

					case "traceoff":
						hw.cpu.setDebug(false);
						break;

					case "meminfo": // Added command to see memory status
						System.out.println("--- Info Memoria ---");
						System.out.println("Tamanho Total: " + so.mm.memSize + " palavras");
						System.out.println("Tamanho Frame/Pagina: " + so.mm.frameSize + " palavras");
						System.out.println("Total de Frames: " + so.mm.totalFrames);
						System.out.println("Frames Livres: " + so.mm.getFreeFrameCount());
						System.out.println("--------------------");
						break;

					case "help":
						System.out.println("Comandos disponiveis:");
						System.out.println(
								"  new <nomePrograma>   - Cria um novo processo a partir de um programa conhecido");
						System.out.println(
								"                         (Programas: " + progs.getAvailableProgramNames() + ")");
						System.out.println("  rm <pid>             - Remove o processo com o ID especificado");
						System.out.println("  ps                   - Lista todos os processos (running/ready)");
						System.out.println("  dump <pid>           - Mostra detalhes do PCB e memoria do processo");
						System.out.println("  dumpm <inicio>,<fim> - Mostra conteudo da memoria fisica no intervalo");
						System.out.println("  exec <pid>           - Executa o processo (apto) com o ID especificado");
						System.out
								.println("  traceon              - Ativa modo de debug da CPU (mostra cada instrucao)");
						System.out.println("  traceoff             - Desativa modo de debug da CPU");
						System.out.println("  meminfo              - Mostra status da memoria (frames livres/totais)");
						System.out.println("  exit                 - Encerra o simulador");
						System.out.println("  help                 - Mostra esta ajuda");
						break;

					case "exit":
						System.out.println("Encerrando o simulador...");
						scanner.close(); // Close scanner
						return; // Exit the run method

					default:
						System.out.println("Comando desconhecido: '" + command + "'. Digite 'help' para ajuda.");
						break;
				}
			} catch (Exception e) { // Catch unexpected errors during command processing
				System.err.println("!!! Erro inesperado processando comando: " + e.getMessage());
				e.printStackTrace(); // Print stack trace for debugging
			}
		} // End while loop
	} // End run()

	// --- Main Method ---
	public static void main(String args[]) {
		// Define memory size and page size
		int memorySize = 1024; // Example: 1KB total memory
		int pageSize = 16; // Example: 16 words per page/frame

		Sistema s = new Sistema(memorySize, pageSize);
		s.run(); // Start the interactive shell
	}

	// --- Program Definitions ---
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

				new Program("fibonacci10v2",
						new Word[] { // mesmo que prog exemplo, so que usa r0 no lugar de r8
								new Word(Opcode.LDI, 1, -1, 0),
								new Word(Opcode.STD, 1, -1, 20),
								new Word(Opcode.LDI, 2, -1, 1),
								new Word(Opcode.STD, 2, -1, 21),
								new Word(Opcode.LDI, 0, -1, 22),
								new Word(Opcode.LDI, 6, -1, 6),
								new Word(Opcode.LDI, 7, -1, 31),
								new Word(Opcode.MOVE, 3, 1, -1),
								new Word(Opcode.MOVE, 1, 2, -1),
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
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1) // ate aqui - serie de fibonacci ficara armazenada
						}),
				new Program("fibonacciREAD",
						new Word[] {
								// mesmo que prog exemplo, so que usa r0 no lugar de r8
								new Word(Opcode.LDI, 8, -1, 1), // leitura
								new Word(Opcode.LDI, 9, -1, 55), // endereco a guardar o tamanho da serie de fib a gerar
																	// - pode ser de 1 a 20
								new Word(Opcode.SYSCALL, -1, -1, -1),
								new Word(Opcode.LDD, 7, -1, 55),
								new Word(Opcode.LDI, 3, -1, 0),
								new Word(Opcode.ADD, 3, 7, -1),
								new Word(Opcode.LDI, 4, -1, 36), // posicao para qual ira pular (stop) *
								new Word(Opcode.LDI, 1, -1, -1), // caso negativo
								new Word(Opcode.STD, 1, -1, 41),
								new Word(Opcode.JMPIL, 4, 7, -1), // pula pra stop caso negativo *
								new Word(Opcode.JMPIE, 4, 7, -1), // pula pra stop caso 0
								new Word(Opcode.ADDI, 7, -1, 41), // fibonacci + posição do stop
								new Word(Opcode.LDI, 1, -1, 0),
								new Word(Opcode.STD, 1, -1, 41), // 25 posicao de memoria onde inicia a serie de
																	// fibonacci gerada
								new Word(Opcode.SUBI, 3, -1, 1), // se 1 pula pro stop
								new Word(Opcode.JMPIE, 4, 3, -1),
								new Word(Opcode.ADDI, 3, -1, 1),
								new Word(Opcode.LDI, 2, -1, 1),
								new Word(Opcode.STD, 2, -1, 42),
								new Word(Opcode.SUBI, 3, -1, 2), // se 2 pula pro stop
								new Word(Opcode.JMPIE, 4, 3, -1),
								new Word(Opcode.LDI, 0, -1, 43),
								new Word(Opcode.LDI, 6, -1, 25), // salva posição de retorno do loop
								new Word(Opcode.LDI, 5, -1, 0), // salva tamanho
								new Word(Opcode.ADD, 5, 7, -1),
								new Word(Opcode.LDI, 7, -1, 0), // zera (inicio do loop)
								new Word(Opcode.ADD, 7, 5, -1), // recarrega tamanho
								new Word(Opcode.LDI, 3, -1, 0),
								new Word(Opcode.ADD, 3, 1, -1),
								new Word(Opcode.LDI, 1, -1, 0),
								new Word(Opcode.ADD, 1, 2, -1),
								new Word(Opcode.ADD, 2, 3, -1),
								new Word(Opcode.STX, 0, 2, -1),
								new Word(Opcode.ADDI, 0, -1, 1),
								new Word(Opcode.SUB, 7, 0, -1),
								new Word(Opcode.JMPIG, 6, 7, -1), // volta para o inicio do loop
								new Word(Opcode.STOP, -1, -1, -1), // POS 36
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1), // POS 41
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1)
						}),
				new Program("PB",
						new Word[] {
								// dado um inteiro em alguma posição de memória,
								// se for negativo armazena -1 na saída; se for positivo responde o fatorial do
								// número na saída
								new Word(Opcode.LDI, 0, -1, 7), // numero para colocar na memoria
								new Word(Opcode.STD, 0, -1, 50),
								new Word(Opcode.LDD, 0, -1, 50),
								new Word(Opcode.LDI, 1, -1, -1),
								new Word(Opcode.LDI, 2, -1, 13), // SALVAR POS STOP
								new Word(Opcode.JMPIL, 2, 0, -1), // caso negativo pula pro STD
								new Word(Opcode.LDI, 1, -1, 1),
								new Word(Opcode.LDI, 6, -1, 1),
								new Word(Opcode.LDI, 7, -1, 13),
								new Word(Opcode.JMPIE, 7, 0, 0), // POS 9 pula pra STD (Stop-1)
								new Word(Opcode.MULT, 1, 0, -1),
								new Word(Opcode.SUB, 0, 6, -1),
								new Word(Opcode.JMP, -1, -1, 9), // pula para o JMPIE
								new Word(Opcode.STD, 1, -1, 15),
								new Word(Opcode.STOP, -1, -1, -1), // POS 14
								new Word(Opcode.DATA, -1, -1, -1) // POS 15
						}),
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
								new Word(Opcode.LDI, 3, -1, 25), // Posicao para pulo CHAVE 1
								new Word(Opcode.STD, 3, -1, 99),
								new Word(Opcode.LDI, 3, -1, 22), // Posicao para pulo CHAVE 2
								new Word(Opcode.STD, 3, -1, 98),
								new Word(Opcode.LDI, 3, -1, 38), // Posicao para pulo CHAVE 3
								new Word(Opcode.STD, 3, -1, 97),
								new Word(Opcode.LDI, 3, -1, 25), // Posicao para pulo CHAVE 4 (não usada)
								new Word(Opcode.STD, 3, -1, 96),
								new Word(Opcode.LDI, 6, -1, 0), // r6 = r7 - 1 POS 22
								new Word(Opcode.ADD, 6, 7, -1),
								new Word(Opcode.SUBI, 6, -1, 1), // ate aqui
								new Word(Opcode.JMPIEM, -1, 6, 97), // CHAVE 3 para pular quando r7 for 1 e r6 0 para
																	// interomper o loop de vez do programa
								new Word(Opcode.LDX, 0, 5, -1), // r0 e ra pegando valores das posições da memoria POS
																// 26
								new Word(Opcode.LDX, 1, 4, -1),
								new Word(Opcode.LDI, 2, -1, 0),
								new Word(Opcode.ADD, 2, 0, -1),
								new Word(Opcode.SUB, 2, 1, -1),
								new Word(Opcode.ADDI, 4, -1, 1),
								new Word(Opcode.SUBI, 6, -1, 1),
								new Word(Opcode.JMPILM, -1, 2, 99), // LOOP chave 1 caso neg procura prox
								new Word(Opcode.STX, 5, 1, -1),
								new Word(Opcode.SUBI, 4, -1, 1),
								new Word(Opcode.STX, 4, 0, -1),
								new Word(Opcode.ADDI, 4, -1, 1),
								new Word(Opcode.JMPIGM, -1, 6, 99), // LOOP chave 1 POS 38
								new Word(Opcode.ADDI, 5, -1, 1),
								new Word(Opcode.SUBI, 7, -1, 1),
								new Word(Opcode.LDI, 4, -1, 0), // r4 = r5 + 1 POS 41
								new Word(Opcode.ADD, 4, 5, -1),
								new Word(Opcode.ADDI, 4, -1, 1), // ate aqui
								new Word(Opcode.JMPIGM, -1, 7, 98), // LOOP chave 2
								new Word(Opcode.STOP, -1, -1, -1), // POS 45
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1)
						})
		};

		// Retrieve program image by name
		public Word[] retrieveProgram(String pname) {
			if (pname == null)
				return null;
			for (Program p : progs) {
				if (p != null && pname.equals(p.name)) { // Use .equals for string comparison
					return p.image;
				}
			}
			return null; // Not found
		}

		// Get list of available program names
		public String getAvailableProgramNames() {
			List<String> names = new ArrayList<>();
			for (Program p : progs) {
				if (p != null)
					names.add(p.name);
			}
			return String.join(", ", names);
		}
	} // --- Fim do Programs ---

} // --- Fim da classe Sistema ---