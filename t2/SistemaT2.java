import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class SistemaT2 {

    // --- Configuração Global do Sistema ---
    private static final int DELTA_T = 5;
    private static final int DISK_SWAP_TIME_MS = 10; // Tempo para operações de disco de paginação
    private static final int IO_DEVICE_TIME_MS = 30; // Tempo para I/O do usuário (input/output)

    // ##################################################################
    // ## 1. NOVAS ESTRUTURAS DE DADOS PARA MEMÓRIA VIRTUAL
    // ##################################################################

    /**
     * Representa uma entrada na Tabela de Páginas de um processo.
     * Contém o mapeamento da página lógica para a memória física ou para o disco.
     */
    public class PageTableEntry {
        public int frameNumber = -1; // Quadro na memória física (-1 se não estiver na memória)
        public boolean validBit = false; // true se a página está na memória física
        public boolean dirtyBit = false; // true se a página foi modificada desde que foi carregada
        public int swapLocation = -1; // Localização no disco de swap (-1 se nunca foi para o swap)
    }

    /**
     * Representa o estado de um quadro (frame) na memória física.
     * Essencial para a política de substituição e para o travamento de I/O.
     */
    public class FrameTableEntry {
        public int pid = -1; // PID do processo que ocupa este quadro
        public int pageNumber = -1; // Número da página lógica que ocupa este quadro
        public boolean pinned = false; // true se o quadro está travado para I/O e não pode ser vitimado
    }

    /**
     * Interrupções que a CPU pode gerar.
     */
    public enum Interrupts {
        noInterrupt, intEnderecoInvalido, intInstrucaoInvalida, intOverflow,
        intSTOP, intTempo,
        intPageFault // NOVA INTERRUPÇÃO PARA FALTA DE PÁGINA
    }

    /**
     * Tipos de operações que o disco de paginação pode realizar.
     */
    public enum PedidoDiscoTipo {
        LOAD_FROM_EXECUTABLE, // Carregar página pela 1ª vez a partir do "executável"
        LOAD_FROM_SWAP, // Carregar página que estava no espaço de swap
        SAVE_TO_SWAP // Salvar uma página vítima (suja) no espaço de swap
    }

    /**
     * Representa uma requisição para o disco de paginação.
     */
    public class PedidoDisco {
        public PedidoDiscoTipo tipo;
        public int pid; // PID do processo que precisa da página
        public int pageNumber; // Página lógica a ser manipulada
        public int frameNumber; // Quadro da memória física envolvido
        public int swapLocation; // Local no swap (se aplicável)
        public String programName; // Nome do programa (para LOAD_FROM_EXECUTABLE)

        // Construtor para operações de disco
        public PedidoDisco(PedidoDiscoTipo tipo, int pid, int pageNumber, int frameNumber, String programName,
                int swapLocation) {
            this.tipo = tipo;
            this.pid = pid;
            this.pageNumber = pageNumber;
            this.frameNumber = frameNumber;
            this.programName = programName;
            this.swapLocation = swapLocation;
        }
    }

    /**
     * Tipos de interrupções geradas pelo disco de paginação.
     */
    public enum InterrupcaoDiscoTipo {
        PAGE_LOAD_COMPLETE, // Carga de página (de qualquer fonte) concluída
        PAGE_SAVE_COMPLETE // Escrita de página no swap concluída
    }

    /**
     * Representa uma notificação de conclusão vinda do disco de paginação.
     */
    public class InterrupcaoDisco {
        public InterrupcaoDiscoTipo tipo;
        public int pid; // PID do processo que originalmente causou a operação
        public int pageNumber; // Página que foi manipulada
        public int frameNumber; // Quadro envolvido
        public int victimPid; // Para PAGE_SAVE_COMPLETE, PID do processo vitimado

        public InterrupcaoDisco(InterrupcaoDiscoTipo tipo, int pid, int pageNumber, int frameNumber, int victimPid) {
            this.tipo = tipo;
            this.pid = pid;
            this.pageNumber = pageNumber;
            this.frameNumber = frameNumber;
            this.victimPid = victimPid;
        }
    }

    // --- ESTRUTURAS DE DADOS BÁSICAS (Memória, Word, Opcode, etc.) ---
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
        DATA, ___, JMP, JMPI, JMPIG, JMPIL, JMPIE, JMPIM, JMPIGM, JMPILM, JMPIEM, JMPIGK, JMPILK, JMPIEK, JMPIGT, ADDI,
        SUBI, ADD, SUB, MULT, LDI, LDD, STD, LDX, STX, MOVE, SYSCALL, STOP
    }

    // ##################################################################
    // ## 2. COMPONENTES DO SO MODIFICADOS E NOVOS
    // ##################################################################

    public class CPU {
        private Word[] m;
        private int pageSize;
        private InterruptHandling ih;
        private SysCallHandling sysCall;
        private int pc;
        private int[] reg;
        private Word ir;
        private Interrupts irpt;
        private int cycleCounter;
        private int quantum;
        private volatile boolean cpuStop;
        private boolean debug;
        private ProcessControlBlock runningProcess; // CPU agora conhece o PCB do processo em execução
        private int faultingAddress = -1;

        public CPU(Memory _mem, boolean _debug, int _pageSize, int _quantum) {
            m = _mem.pos;
            pageSize = _pageSize;
            quantum = _quantum;
            debug = _debug;
            reg = new int[10];
            cpuStop = true;
        }

        /**
         * Carrega o contexto de um processo para execução.
         */
        public void setContext(ProcessControlBlock pcb) {
            this.runningProcess = pcb;
            this.pc = pcb.contexto.pc;
            System.arraycopy(pcb.contexto.regs, 0, this.reg, 0, pcb.contexto.regs.length);
            this.irpt = Interrupts.noInterrupt;
            this.cycleCounter = 0;
            this.cpuStop = false;
            if (debug)
                System.out.println("CPU: Contexto carregado para PID " + pcb.pid + " (PC=" + pc + ")");
        }

        /**
         * Traduz um endereço lógico para físico.
         * Esta é a função central que aciona a Memória Virtual.
         * 
         * @param logicalAddress Endereço a ser traduzido.
         * @param isWrite        Indica se a operação é de escrita (para setar o
         *                       dirtyBit).
         * @return Endereço físico, ou -1 se ocorrer um page fault ou erro.
         */
        private int translateAddress(int logicalAddress, boolean isWrite) {
            if (runningProcess == null) {
                irpt = Interrupts.intEnderecoInvalido;
                return -1;
            }
            int pageNumber = logicalAddress / pageSize;
            int offset = logicalAddress % pageSize;

            if (pageNumber < 0 || pageNumber >= runningProcess.pageTable.length) {
                irpt = Interrupts.intEnderecoInvalido;
                return -1;
            }

            PageTableEntry pte = runningProcess.pageTable[pageNumber];

            // O ponto chave da memória virtual: a página está na memória?
            if (!pte.validBit) {
                System.out.println(
                        ">>> CPU: Endereço " + logicalAddress + " (Página " + pageNumber + ") não está na memória.");
                irpt = Interrupts.intPageFault; // Gera a interrupção!
                faultingAddress = logicalAddress;
                return -1;
            }

            // Se a operação é de escrita, marca a página como "suja".
            if (isWrite) {
                pte.dirtyBit = true;
            }

            return (pte.frameNumber * pageSize) + offset;
        }

        public void run() {
            if (runningProcess == null) {
                cpuStop = true;
                return;
            }
            if (cpuStop)
                return;

            while (!cpuStop && irpt == Interrupts.noInterrupt) {
                int physicalPC = translateAddress(pc, false);
                if (irpt != Interrupts.noInterrupt)
                    break;

                ir = m[physicalPC];
                if (debug)
                    System.out
                            .print("  PC_log=" + pc + " IR=[" + ir.opc + "," + ir.ra + "," + ir.rb + "," + ir.p + "]");

                Opcode currentOpc = ir.opc;
                int physicalAddress;

                switch (currentOpc) {
                    // Instruções de acesso à memória
                    case LDD:
                        physicalAddress = translateAddress(ir.p, false);
                        if (irpt != Interrupts.noInterrupt)
                            break;
                        reg[ir.ra] = m[physicalAddress].p;
                        pc++;
                        break;
                    case STD:
                        physicalAddress = translateAddress(ir.p, true);
                        if (irpt != Interrupts.noInterrupt)
                            break;
                        m[physicalAddress].opc = Opcode.DATA;
                        m[physicalAddress].p = reg[ir.ra];
                        pc++;
                        break;
                    case LDX:
                        physicalAddress = translateAddress(reg[ir.rb], false);
                        if (irpt != Interrupts.noInterrupt)
                            break;
                        reg[ir.ra] = m[physicalAddress].p;
                        pc++;
                        break;
                    case STX:
                        physicalAddress = translateAddress(reg[ir.ra], true);
                        if (irpt != Interrupts.noInterrupt)
                            break;
                        m[physicalAddress].opc = Opcode.DATA;
                        m[physicalAddress].p = reg[ir.rb];
                        pc++;
                        break;
                    // Instruções que não acessam memória (exceto para Jumps indiretos)
                    case LDI:
                        reg[ir.ra] = ir.p;
                        pc++;
                        break;
                    case ADD:
                        reg[ir.ra] += reg[ir.rb];
                        pc++;
                        break;
                    case ADDI:
                        reg[ir.ra] += ir.p;
                        pc++;
                        break;
                    case SUB:
                        reg[ir.ra] -= reg[ir.rb];
                        pc++;
                        break;
                    case SUBI:
                        reg[ir.ra] -= ir.p;
                        pc++;
                        break;
                    case MULT:
                        reg[ir.ra] *= reg[ir.rb];
                        pc++;
                        break;
                    case MOVE:
                        reg[ir.ra] = reg[ir.rb];
                        pc++;
                        break;

                    // Saltos
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

                    // Chamadas de sistema e parada
                    case SYSCALL:
                        pc++;
                        sysCall.handle(runningProcess);
                        break;
                    case STOP:
                        irpt = Interrupts.intSTOP;
                        break;

                    default:
                        irpt = Interrupts.intInstrucaoInvalida;
                        break;
                }

                if (irpt == Interrupts.noInterrupt) {
                    cycleCounter++;
                    if (cycleCounter >= quantum)
                        irpt = Interrupts.intTempo;
                }
            }

            // Trata a interrupção que parou o loop
            if (irpt != Interrupts.noInterrupt) {
                ih.handle(irpt, pc);
            }
            // cpuStop = true;
        }

        // Getters/Setters e outros métodos auxiliares
        public void setAddressOfHandlers(InterruptHandling _ih, SysCallHandling _sysCall) {
            this.ih = _ih;
            this.sysCall = _sysCall;
        }

        public int getPC() {
            return pc;
        }

        public int[] getRegs() {
            return Arrays.copyOf(reg, reg.length);
        }

        public int getFaultingAddress() {
            return faultingAddress;
        }

        public void stopCPU() {
            this.cpuStop = true;
        }

        public void setDebug(boolean d) {
            this.debug = d;
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
                System.err.println("IH: ERRO CRÍTICO - Interrupção " + irpt + " sem processo rodando!");
                cpu.stopCPU();
                return;
            }
            System.out.println("--------------------------------------------------------------------");
            System.out.println(">>> INTERRUPÇÃO: " + irpt + " no PID " + currentProcess.pid + " (PC lógico="
                    + interruptedPC + ")");

            switch (irpt) {
                case intPageFault:
                    currentProcess.contexto.pc = interruptedPC; // Salva o PC exato da falha
                    so.gp.saveContext(currentProcess); // Salva o resto (registradores)
                    so.gp.blockProcess(currentProcess, "PAGE_FAULT");
                    int faultAddress = cpu.getFaultingAddress();
                    so.mm.handlePageFault(currentProcess, faultAddress);
                    so.gp.schedule();
                    break;

                case intTempo:
                    so.gp.preempt(currentProcess);
                    break;
                case intSTOP:
                    so.gp.terminateProcess(currentProcess);
                    so.gp.schedule();
                    break;
                default: // Erros irrecuperáveis
                    System.err.println("IH: Erro irrecuperável. Terminando processo " + currentProcess.pid);
                    so.gp.terminateProcess(currentProcess);
                    so.gp.schedule();
                    break;
            }
            System.out.println("--------------------------------------------------------------------");
        }
    }

    public class ProcessControlBlock {
        public int pid;
        public PageTableEntry[] pageTable;
        public Contexto contexto;
        public String programName;
        public ProcessState state;
        public int programSize;
        private String blockedReason = "";

        public ProcessControlBlock(int pid, int programSize, String programName, int pageSize) {
            this.pid = pid;
            this.programName = programName;
            this.programSize = programSize;
            this.contexto = new Contexto();
            this.state = ProcessState.BLOCKED; // Inicia bloqueado esperando a 1ª página
            this.blockedReason = "PAGE_FAULT";

            int numPages = (int) Math.ceil((double) programSize / pageSize);
            this.pageTable = new PageTableEntry[numPages];
            for (int i = 0; i < numPages; i++) {
                this.pageTable[i] = new PageTableEntry();
            }
        }

        public void setBlockedReason(String reason) {
            this.blockedReason = reason;
        }

        public String getBlockedReason() {
            return this.blockedReason;
        }
    }

    public class Contexto {
        public int[] regs;
        public int pc;

        public Contexto() {
            this.pc = 0;
            this.regs = new int[10];
        }
    }

    public enum ProcessState {
        READY, RUNNING, BLOCKED, TERMINATED
    }

    public class SysCallHandling {
        private SO so;

        public SysCallHandling(SO _so) {
            this.so = _so;
        }

        public void handle(ProcessControlBlock pcb) {
            so.gp.blockProcessForIO(pcb, pcb.contexto.regs[9], pcb.contexto.regs[8] == 1);
            so.hw.cpu.stopCPU();
        }
    }

    public class Utilities {
        private HW hw;

        public Utilities(HW _hw) {
            hw = _hw;
        }

        public void dump(int ini, int fim) {
            /* ... */ }
    }

    // --- NOVOS GERENCIADORES DE DISCO E MEMÓRIA VIRTUAL ---

    public class DiskManager {
        private Map<Integer, Word[]> swapSpace = new ConcurrentHashMap<>();
        private Set<Integer> freeSwapSlots = new HashSet<>();
        private AtomicInteger nextSwapSlot = new AtomicInteger(0);

        public synchronized int allocateSwapSlot() {
            if (!freeSwapSlots.isEmpty()) {
                int slot = freeSwapSlots.iterator().next();
                freeSwapSlots.remove(slot);
                return slot;
            }
            return nextSwapSlot.getAndIncrement();
        }

        public synchronized void freeSwapSlot(int slot) {
            swapSpace.remove(slot);
            freeSwapSlots.add(slot);
        }

        public void savePageToSwap(int swapSlot, int frameNumber, int pageSize) {
            Word[] pageData = new Word[pageSize];
            System.arraycopy(so.hw.mem.pos, frameNumber * pageSize, pageData, 0, pageSize);
            swapSpace.put(swapSlot, pageData);
        }

        public void loadPageFromSwap(int swapSlot, int frameNumber, int pageSize) {
            Word[] pageData = swapSpace.get(swapSlot);
            System.arraycopy(pageData, 0, so.hw.mem.pos, frameNumber * pageSize, pageSize);
        }

        public void loadPageFromExecutable(String programName, int pageNumber, int frameNumber, int pageSize) {
            Word[] programImage = so.progs.retrieveProgram(programName);
            if (programImage == null) {
                System.err.println("DISCO: ERRO CRÍTICO - Tentando carregar página de programa não encontrado: " + programName);
                for (int i = 0; i < pageSize; i++) {
                    so.hw.mem.pos[frameNumber * pageSize + i] = new Word(Opcode.___, -1, -1, -1);
                }
                return;
            }

            int pageStartInImage = pageNumber * pageSize;
            int wordsToCopy = Math.min(pageSize, programImage.length - pageStartInImage);

            System.arraycopy(programImage, pageStartInImage, so.hw.mem.pos, frameNumber * pageSize, wordsToCopy);

            if (wordsToCopy < pageSize) {
                for (int i = wordsToCopy; i < pageSize; i++) {
                    so.hw.mem.pos[frameNumber * pageSize + i] = new Word(Opcode.___, -1, -1, -1);
                }
            }
        }
    }

    public class MemoryManagment {
        private FrameTableEntry[] frameTable;
        private Queue<Integer> fifoQueue;
        private Lock lock = new ReentrantLock();

        public MemoryManagment(int tamMem, int tamFrame) {
            int totalFrames = tamMem / tamFrame;
            this.frameTable = new FrameTableEntry[totalFrames];
            for (int i = 0; i < totalFrames; i++)
                this.frameTable[i] = new FrameTableEntry();
            this.fifoQueue = new LinkedList<>();
            System.out.println("GM: Memória Virtual com " + totalFrames + " frames e política FIFO.");
        }

        public void handlePageFault(ProcessControlBlock pcb, int logicalAddress) {
            int pageNumber = logicalAddress / so.hw.pageSize;
            System.out.println("GM: Tratando Page Fault para PID " + pcb.pid + ", Página " + pageNumber);
            getFreeFrame(pcb, pageNumber);
        }

        private void getFreeFrame(ProcessControlBlock pcb, int pageNumber) {
            lock.lock();
            try {
                // 1. Tenta achar um frame livre
                for (int i = 0; i < frameTable.length; i++) {
                    if (frameTable[i].pid == -1) {
                        System.out.println("GM: Frame livre encontrado: " + i);
                        pinFrame(i, pcb.pid, pageNumber);
                        fifoQueue.add(i);
                        loadPageIntoFrame(pcb, pageNumber, i);
                        return;
                    }
                }

                // 2. Não achou, precisa vitimar
                System.out.println("GM: Memória cheia. Iniciando política de substituição FIFO.");
                for (int i = 0; i < fifoQueue.size(); i++) {
                    int victimFrame = fifoQueue.poll();
                    if (!frameTable[victimFrame].pinned) {
                        System.out.println("GM: Frame " + victimFrame + " escolhido como vítima.");
                        prepareVictimFrame(victimFrame, pcb, pageNumber);
                        fifoQueue.add(victimFrame); // Re-enfileira o frame, que será ocupado pelo novo processo
                        return;
                    } else {
                        fifoQueue.add(victimFrame); // Põe de volta no fim da fila se estiver pinado
                    }
                }
                System.err.println(
                        "GM: ERRO CRÍTICO! Não foi possível encontrar um frame para alocar (todos estão pinados?).");
            } finally {
                lock.unlock();
            }
        }

        private void prepareVictimFrame(int frameNumber, ProcessControlBlock newPcb, int newPage) {
            FrameTableEntry victimFrameInfo = frameTable[frameNumber];
            ProcessControlBlock victimPcb = so.gp.getProcess(victimFrameInfo.pid);
            PageTableEntry victimPte = victimPcb.pageTable[victimFrameInfo.pageNumber];

            victimPte.validBit = false;

            if (victimPte.dirtyBit) {
                System.out.println("GM: Vítima (PID " + victimPcb.pid + ", Pag " + victimFrameInfo.pageNumber
                        + ") está suja. Salvando no swap.");
                int swapSlot = so.diskManager.allocateSwapSlot();
                victimPte.swapLocation = swapSlot;
                pinFrame(frameNumber, newPcb.pid, newPage); // Pina para o novo processo (que está esperando o save)
                so.filaDisco.add(new PedidoDisco(PedidoDiscoTipo.SAVE_TO_SWAP, newPcb.pid, newPage, frameNumber, null,
                        swapSlot));
            } else {
                System.out.println("GM: Vítima não está suja. Frame pode ser usado imediatamente.");
                pinFrame(frameNumber, newPcb.pid, newPage);
                loadPageIntoFrame(newPcb, newPage, frameNumber);
            }
        }

        public void handlePageSaveCompletion(int pid, int pageNumber, int frameNumber) {
            System.out.println(
                    "GM: Notificado que save do frame " + frameNumber + " terminou. Carregando página para PID " + pid);
            loadPageIntoFrame(so.gp.getProcess(pid), pageNumber, frameNumber);
        }

        private void loadPageIntoFrame(ProcessControlBlock pcb, int pageNumber, int frameNumber) {
            PageTableEntry pte = pcb.pageTable[pageNumber];
            PedidoDiscoTipo tipo = (pte.swapLocation != -1) ? PedidoDiscoTipo.LOAD_FROM_SWAP
                    : PedidoDiscoTipo.LOAD_FROM_EXECUTABLE;
            so.filaDisco
                    .add(new PedidoDisco(tipo, pcb.pid, pageNumber, frameNumber, pcb.programName, pte.swapLocation));
        }

        public void finalizePageLoad(int pid, int pageNumber, int frameNumber) {
            lock.lock();
            try {
                ProcessControlBlock pcb = so.gp.getProcess(pid);
                if (pcb != null) {
                    PageTableEntry pte = pcb.pageTable[pageNumber];
                    pte.frameNumber = frameNumber;
                    pte.validBit = true;
                    pte.dirtyBit = false;
                    unpinFrame(frameNumber);
                    so.gp.unblockProcess(pid, "PAGE_FAULT");
                }
            } finally {
                lock.unlock();
            }
        }

        public void pinFrame(int frame, int pid, int page) {
            lock.lock();
            try {
                frameTable[frame].pinned = true;
                frameTable[frame].pid = pid;
                frameTable[frame].pageNumber = page;
            } finally {
                lock.unlock();
            }
        }

        public void unpinFrame(int frame) {
            lock.lock();
            try {
                frameTable[frame].pinned = false;
            } finally {
                lock.unlock();
            }
        }

        public FrameTableEntry getFrameOwner(int frame) {
            return frameTable[frame];
        }

        public void desaloca(ProcessControlBlock pcb) {
            lock.lock();
            try {
                for (PageTableEntry pte : pcb.pageTable) {
                    if (pte.validBit) {
                        frameTable[pte.frameNumber] = new FrameTableEntry();
                        fifoQueue.remove(pte.frameNumber);
                    }
                    if (pte.swapLocation != -1)
                        so.diskManager.freeSwapSlot(pte.swapLocation);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    public class ProcessManagement {
        private Map<Integer, ProcessControlBlock> allProcesses;
        private LinkedList<ProcessControlBlock> aptos;
        private LinkedList<ProcessControlBlock> bloqueados;
        private volatile ProcessControlBlock running;
        private CPU cpu;
        private MemoryManagment mm;
        private AtomicInteger nextPid = new AtomicInteger(0);
        private Lock schedulerLock = new ReentrantLock();

        public ProcessManagement(CPU _cpu, MemoryManagment _mm) {
            this.cpu = _cpu;
            this.mm = _mm;
            this.aptos = new LinkedList<>();
            this.bloqueados = new LinkedList<>();
            this.allProcesses = new ConcurrentHashMap<>();
        }

        public ProcessControlBlock getProcess(int pid) {
            return allProcesses.get(pid);
        }

        public void criaProcesso(Word[] programa, String programName) {
            schedulerLock.lock();
            try {
                int pid = nextPid.getAndIncrement();
                ProcessControlBlock pcb = new ProcessControlBlock(pid, programa.length, programName, so.hw.pageSize);
                allProcesses.put(pid, pcb);
                bloqueados.add(pcb);
                System.out.println("GP: Processo " + pid + " (" + programName
                        + ") criado. Forçando page fault inicial para a página 0.");
                so.mm.handlePageFault(pcb, 0); // O processo começa bloqueado e espera a carga da 1ª página
            } finally {
                schedulerLock.unlock();
            }
        }

        public void schedule() {
            schedulerLock.lock();
            try {
                if (running != null)
                    return;

                if (!aptos.isEmpty()) {
                    running = aptos.removeFirst();
                    running.state = ProcessState.RUNNING;
                    System.out.println("GP: Escalonando processo -> PID: " + running.pid);
                    cpu.setContext(running);
                    so.cpuExecutionManager.signalCpuToRun();
                } else {
                    running = null;
                    System.out.println("GP: Fila de aptos VAZIA. CPU ociosa.");
                    cpu.stopCPU();
                }
            } finally {
                schedulerLock.unlock();
            }
        }

        public void blockProcess(ProcessControlBlock pcb, String reason) {
            schedulerLock.lock();
            try {
                if (pcb.state == ProcessState.RUNNING) {
                    running = null;
                } else {
                    aptos.remove(pcb);
                }
                pcb.state = ProcessState.BLOCKED;
                pcb.setBlockedReason(reason);
                bloqueados.add(pcb);
            } finally {
                schedulerLock.unlock();
            }
        }

        public void unblockProcess(int pid, String reason) {
            schedulerLock.lock();
            try {
                ProcessControlBlock pcb = getProcess(pid);
                if (pcb != null && pcb.state == ProcessState.BLOCKED && pcb.getBlockedReason().equals(reason)) {
                    bloqueados.remove(pcb);
                    addToReadyQueue(pcb);
                    if (running == null)
                        schedule();
                }
            } finally {
                schedulerLock.unlock();
            }
        }

        public void preempt(ProcessControlBlock pcb) {
            schedulerLock.lock();
            try {
                if (pcb != null && pcb.state == ProcessState.RUNNING) {
                    saveContext(pcb);
                    addToReadyQueue(pcb);
                    running = null;
                    schedule();
                }
            } finally {
                schedulerLock.unlock();
            }
        }

        // ... (outros métodos como terminateProcess, saveContext, etc. adaptados)
        public void saveContext(ProcessControlBlock pcb) {
            if (pcb != null) {
                pcb.contexto.pc = cpu.getPC();
                pcb.contexto.regs = cpu.getRegs();
            }
        }

        public void addToReadyQueue(ProcessControlBlock pcb) {
            if (pcb != null) {
                pcb.state = ProcessState.READY;
                aptos.add(pcb);
            }
        }

        public void blockProcessForIO(ProcessControlBlock pcb, int address, boolean isRead) {
            saveContext(pcb);
            blockProcess(pcb, "IO");
            so.filaIO.add(new PedidoIO(pcb.pid, address, isRead));
            schedule();
        }

        public ProcessControlBlock getRunningProcess() {
            return running;
        }

        public void terminateProcess(ProcessControlBlock pcb) {
            schedulerLock.lock();
            try {
                if (pcb == null)
                    return;
                System.out.println("GP: Terminando processo PID: " + pcb.pid);
                mm.desaloca(pcb);
                allProcesses.remove(pcb.pid);
                aptos.remove(pcb);
                bloqueados.remove(pcb);
                if (running == pcb)
                    running = null;
            } finally {
                schedulerLock.unlock();
            }
        }

        public void listProcesses() {
            schedulerLock.lock();
            try {
                System.out.println("\n--- LISTA DE PROCESSOS ---");
                if (allProcesses.isEmpty()) {
                    System.out.println("Nenhum processo no sistema.");
                    return;
                }
                allProcesses.values().forEach(pcb -> {
                    System.out.print("  PID: " + pcb.pid + " | Nome: '" + pcb.programName + "' | Estado: " + pcb.state);
                    if (pcb.state == ProcessState.BLOCKED)
                        System.out.print(" (" + pcb.getBlockedReason() + ")");
                    System.out.println(" | PC: " + pcb.contexto.pc);
                });
                System.out.println("--------------------------");
            } finally {
                schedulerLock.unlock();
            }
        }

        public void dumpProcess(int pid) {
            schedulerLock.lock();
            try {
                ProcessControlBlock pcb = getProcess(pid);
                if (pcb == null) {
                    System.out.println("Processo PID " + pid + " não encontrado.");
                    return;
                }
                System.out.println("\n--- DUMP DO PROCESSO PID: " + pid + " ---");
                System.out.println("  Estado: " + pcb.state);
                System.out.println("  Tabela de Páginas (" + pcb.pageTable.length + " páginas):");
                for (int i = 0; i < pcb.pageTable.length; i++) {
                    PageTableEntry pte = pcb.pageTable[i];
                    System.out.printf("    Pag %2d: Válida=%-5b | Suja=%-5b | Frame=%-3s | Swap=%-3s%n", i,
                            pte.validBit, pte.dirtyBit, (pte.validBit ? pte.frameNumber : "N/A"),
                            (pte.swapLocation != -1 ? pte.swapLocation : "N/A"));
                }
                System.out.println("---------------------------------");
            } finally {
                schedulerLock.unlock();
            }
        }
    }

    public class DispositivoIO extends Thread {
        @Override
        public void run() {
            while (!so.shutdown) {
                try {
                    PedidoIO pedido = so.filaIO.take();
                    if (so.shutdown)
                        break;

                    ProcessControlBlock pcb = so.gp.getProcess(pedido.pid);
                    if (pcb == null || pcb.state != ProcessState.BLOCKED)
                        continue;

                    int pageNumber = pedido.endLogico / so.hw.pageSize;
                    PageTableEntry pte = pcb.pageTable[pageNumber];

                    if (!pte.validBit) {
                        System.err.println("IO DEV: ERRO FATAL! PID " + pcb.pid + " pediu I/O para página " + pageNumber
                                + " que não está na memória.");
                        continue;
                    }

                    int frameNumber = pte.frameNumber;
                    System.out.println("IO DEV: Iniciando I/O para PID " + pcb.pid + ". Travando frame " + frameNumber);
                    so.mm.pinFrame(frameNumber, pcb.pid, pageNumber);

                    Thread.sleep(IO_DEVICE_TIME_MS);

                    if (pedido.isRead) {
                        // ... Lógica de input ...
                    } else {
                        // ... Lógica de output ...
                    }

                    System.out.println("IO DEV: I/O concluído. Liberando frame " + frameNumber);
                    so.mm.unpinFrame(frameNumber);
                    so.filaInterrupcoesIO.put(pedido.pid);

                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    // --- THREADS E PONTO DE ENTRADA ---

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
                        case "input":
                            try {
                                String[] inputArgs = args.split("\\s+");
                                if (inputArgs.length != 2) {
                                    System.out.println("Uso: input <pid> <valor>");
                                    break;
                                }
                                int pid = Integer.parseInt(inputArgs[0]);
                                int valor = Integer.parseInt(inputArgs[1]);

                                if (so.ioManager.provideInput(pid, valor)) {
                                    System.out.println(
                                            "<SHELL> Valor " + valor + " enviado para o processo PID " + pid + ".");
                                } else {
                                    System.out.println("<SHELL> Erro: Processo PID " + pid
                                            + " não está aguardando por um input no momento.");
                                }
                            } catch (NumberFormatException e) {
                                System.out.println("Erro: PID e valor devem ser números inteiros.");
                            }
                            break;

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

    public class IOInterruptHandlerThread extends Thread {
        @Override
        public void run() {
            while (!so.shutdown) {
                try {
                    Integer pid = so.filaInterrupcoesIO.take();
                    if (so.shutdown)
                        break;
                    so.gp.unblockProcess(pid, "IO");
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    public class PedidoIO {
        public int pid, endLogico;
        public boolean isRead;

        public PedidoIO(int p, int e, boolean r) {
            pid = p;
            endLogico = e;
            isRead = r;
        }
    }

    public class HW {
        public Memory mem;
        public CPU cpu;
        public int pageSize;

        public HW(int tm, int ps, int q) {
            mem = new Memory(tm);
            pageSize = ps;
            cpu = new CPU(mem, false, ps, q);
        }
    }

    public class IOManager {
        // Mapeia um PID para uma fila que conterá o valor de input esperado.
        // Usamos BlockingQueue para que a thread que lê (DispositivoIO) possa esperar
        // (ficar bloqueada)
        // até que a thread que escreve (Shell) coloque um item na fila.
        private final ConcurrentHashMap<Integer, BlockingQueue<Integer>> pendingInputs;

        public IOManager() {
            this.pendingInputs = new ConcurrentHashMap<>();
        }

        /**
         * Chamado pelo DispositivoIO quando um processo precisa de um input.
         * Este método bloqueia a thread chamadora até que um valor seja fornecido.
         * 
         * @param pid O PID do processo que aguarda input.
         * @return O valor de input que foi fornecido.
         * @throws InterruptedException se a thread for interrompida enquanto espera.
         */
        public int awaitInput(int pid) throws InterruptedException {
            BlockingQueue<Integer> inputQueue = new LinkedBlockingQueue<>(1); // Fila com capacidade 1
            pendingInputs.put(pid, inputQueue);

            System.out.println("\n<SISTEMA> Processo PID " + pid + " aguardando por um input. Use o comando 'input "
                    + pid + " <valor>'.");

            // A MÁGICA ACONTECE AQUI:
            // A thread do DispositivoIO vai ficar bloqueada em .take() até que
            // a thread da Shell chame .provideInput() e coloque um valor na fila.
            int valor = inputQueue.take();

            pendingInputs.remove(pid); // Remove da lista de pendentes após receber o valor
            return valor;
        }

        /**
         * Chamado pela Shell para fornecer um valor de input a um processo.
         * 
         * @param pid   O PID do processo que receberá o valor.
         * @param value O valor a ser entregue.
         * @return true se o processo estava aguardando input, false caso contrário.
         */
        public boolean provideInput(int pid, int value) {
            BlockingQueue<Integer> inputQueue = pendingInputs.get(pid);
            if (inputQueue != null) {
                // Oferece o valor para a fila. Como a capacidade é 1,
                // e a outra thread está bloqueada em take(), isso sempre funcionará.
                inputQueue.offer(value);
                return true;
            } else {
                // O processo não estava esperando por input.
                return false;
            }
        }
    }

    public class SO {
        public HW hw;
        public CPU cpu;
        public MemoryManagment mm;
        public ProcessManagement gp;
        public DiskManager diskManager;
        public CpuExecutionManager cpuExecutionManager;
        public volatile boolean shutdown = false;
        public BlockingQueue<PedidoIO> filaIO = new LinkedBlockingQueue<>();
        public BlockingQueue<Integer> filaInterrupcoesIO = new LinkedBlockingQueue<>();
        public BlockingQueue<PedidoDisco> filaDisco = new LinkedBlockingQueue<>();
        public BlockingQueue<InterrupcaoDisco> filaInterrupcoesDisco = new LinkedBlockingQueue<>();
        public SysCallHandling sc;
        public InterruptHandling ih;
        public Utilities utils;
        public Programs progs;
        public IOManager ioManager;

        public SO(HW _hw) {
            this.hw = _hw;
            this.cpu = hw.cpu;
            this.mm = new MemoryManagment(hw.mem.getSize(), hw.pageSize);
            this.gp = new ProcessManagement(cpu, mm);
            this.diskManager = new DiskManager();
            this.cpuExecutionManager = new CpuExecutionManager(cpu, this);
            this.sc = new SysCallHandling(this);
            this.ih = new InterruptHandling(this);
            this.utils = new Utilities(hw);
            this.progs = new Programs();
            this.ioManager = new IOManager();
            cpu.setAddressOfHandlers(ih, sc);
        }
    }

    public HW hw;
    public SO so;
    public Programs progs;

    public SistemaT2(int tamMem, int pageSize) {
        hw = new HW(tamMem, pageSize, DELTA_T);
        so = new SO(hw);
        progs = new Programs();
        System.out.println("Sistema: Hardware e SO criados. Pronto para comandos.");
    }

    public class DiskDeviceThread extends Thread {
        @Override
        public void run() {
            while (!so.shutdown) {
                try {
                    PedidoDisco pedido = so.filaDisco.take();
                    if (so.shutdown)
                        break;

                    Thread.sleep(DISK_SWAP_TIME_MS);

                    int oldPid = -1; // Usado para notificar sobre a conclusão do SAVE
                    if (pedido.tipo == PedidoDiscoTipo.SAVE_TO_SWAP) {
                        so.diskManager.savePageToSwap(pedido.swapLocation, pedido.frameNumber, so.hw.pageSize);
                        oldPid = so.mm.getFrameOwner(pedido.frameNumber).pid;
                        so.mm.unpinFrame(pedido.frameNumber); // Libera o frame após salvar
                        System.out.println("DISCO: Página do PID " + oldPid + " salva no swap. Frame "
                                + pedido.frameNumber + " livre.");
                        so.filaInterrupcoesDisco.put(new InterrupcaoDisco(InterrupcaoDiscoTipo.PAGE_SAVE_COMPLETE,
                                pedido.pid, pedido.pageNumber, pedido.frameNumber, oldPid));
                    } else { // É um LOAD
                        if (pedido.tipo == PedidoDiscoTipo.LOAD_FROM_EXECUTABLE) {
                            so.diskManager.loadPageFromExecutable(pedido.programName, pedido.pageNumber,
                                    pedido.frameNumber, so.hw.pageSize);
                        } else { // LOAD_FROM_SWAP
                            so.diskManager.loadPageFromSwap(pedido.swapLocation, pedido.frameNumber, so.hw.pageSize);
                        }
                        so.mm.unpinFrame(pedido.frameNumber);
                        System.out.println("DISCO: Página " + pedido.pageNumber + " do PID " + pedido.pid
                                + " carregada no frame " + pedido.frameNumber);
                        so.filaInterrupcoesDisco.put(new InterrupcaoDisco(InterrupcaoDiscoTipo.PAGE_LOAD_COMPLETE,
                                pedido.pid, pedido.pageNumber, pedido.frameNumber, -1));
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }
    public class DiskInterruptHandlerThread extends Thread {
		@Override
		public void run() {
			while(!so.shutdown) {
				try {
					InterrupcaoDisco irpt = so.filaInterrupcoesDisco.take();
					if (irpt.tipo == InterrupcaoDiscoTipo.PAGE_LOAD_COMPLETE) {
						so.mm.finalizePageLoad(irpt.pid, irpt.pageNumber, irpt.frameNumber);
					} else { // PAGE_SAVE_COMPLETE
						so.mm.handlePageSaveCompletion(irpt.pid, irpt.pageNumber, irpt.frameNumber);
					}
				} catch (InterruptedException e) { break; }
			}
		}
	}

    public void startSystem() {
        new ShellThread().start();
        so.cpuExecutionManager.start();
        new IOInterruptHandlerThread().start();
        new DispositivoIO().start();
        new DiskDeviceThread().start();
        new DiskInterruptHandlerThread().start();
        System.out.println("--- Sistema com Memória Virtual INICIADO ---");
    }

    public static void main(String[] args) {
        // Memória menor para forçar swapping mais rápido
        SistemaT2 s = new SistemaT2(1200, 10);
        s.startSystem();
    }

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
                            }),
                    new Program("fatorial",
                            new Word[] {
                                    // este fatorial so aceita valores positivos. nao pode ser zero
                                    // linha coment
                                    new Word(Opcode.LDI, 0, -1, 7), // 0 r0 é valor a calcular fatorial
                                    new Word(Opcode.LDI, 1, -1, 1), // 1 r1 é 1 para multiplicar (por r0)
                                    new Word(Opcode.LDI, 6, -1, 1), // 2 r6 é 1 o decremento
                                    new Word(Opcode.LDI, 7, -1, 8), // 3 r7 tem posicao 8 para fim do programa
                                    new Word(Opcode.JMPIE, 7, 0, 0), // 4 se r0=0 pula para r7(=8)
                                    new Word(Opcode.MULT, 1, 0, -1), // 5 r1 = r1 * r0 (r1 acumula o produto por cada
                                                                     // termo)
                                    new Word(Opcode.SUB, 0, 6, -1), // 6 r0 = r0 - r6 (r6=1) decrementa r0 para proximo
                                                                    // termo
                                    new Word(Opcode.JMP, -1, -1, 4), // 7 vai p posicao 4
                                    new Word(Opcode.STD, 1, -1, 10), // 8 coloca valor de r1 na posição 10
                                    new Word(Opcode.STOP, -1, -1, -1), // 9 stop
                                    new Word(Opcode.DATA, -1, -1, -1) // 10 ao final o valor está na posição 10 da
                                                                      // memória
                            }),

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
                                    new Word(Opcode.LDI, 3, -1, 25), // Posicao para pulo CHAVE 1 -> JMPILM target needs
                                                                     // to
                                                                     // be
                                                                     // logical addr 25
                                    new Word(Opcode.STD, 3, -1, 99), // Storing jump target addr 25 at logical addr 99
                                    new Word(Opcode.LDI, 3, -1, 22), // Posicao para pulo CHAVE 2 -> JMPIGM target needs
                                                                     // to
                                                                     // be
                                                                     // logical addr 22
                                    new Word(Opcode.STD, 3, -1, 98), // Storing jump target addr 22 at logical addr 98
                                    new Word(Opcode.LDI, 3, -1, 45), // Posicao para pulo CHAVE 3 -> JMPIEM target needs
                                                                     // to
                                                                     // be
                                                                     // logical addr 45 (STOP)
                                    new Word(Opcode.STD, 3, -1, 97), // Storing jump target addr 45 at logical addr 97
                                    new Word(Opcode.LDI, 3, -1, 25), // Posicao para pulo CHAVE 4 -> JMPIGM target needs
                                                                     // to
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
                                    new Word(Opcode.LDX, 1, 4, -1), // LDX R1, R4 <- Loads from M[Logical Addr in R4]
                                                                    // into
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
                                    new Word(Opcode.DATA, -1, -1, 45), // 97: Target for JMPIEM (outer loop finish ->
                                                                       // STOP)
                                    new Word(Opcode.DATA, -1, -1, 22), // 98: Target for JMPIGM (outer loop cont)
                                    new Word(Opcode.DATA, -1, -1, 25) // 99: Target for JMPILM (inner loop cont, no
                                                                      // swap)
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

}
